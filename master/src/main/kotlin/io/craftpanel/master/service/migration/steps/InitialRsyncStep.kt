package io.craftpanel.master.service.migration.steps

import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.domain.MigrationStatus
import io.craftpanel.master.service.migration.MigrationContext
import io.craftpanel.master.service.migration.MigrationStep
import io.craftpanel.master.service.migration.StepResult
import io.craftpanel.proto.masterMessage
import io.craftpanel.proto.startRsyncCommand
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

class InitialRsyncStep : MigrationStep {

    override val stepNumber = 3
    override val description = "Initial rsync pass (live data sync)"

    override suspend fun execute(ctx: MigrationContext): StepResult {
        val completeChannel = Channel<AgentEvent.RsyncCompleteEvent>(1)
        val progressJob = ctx.scope.launch {
            ctx.gateway.agentEvents.filterIsInstance<AgentEvent.RsyncProgressEvent>()
                .collect { u ->
                    if (u.migrationId == ctx.migrationIdStr && !u.isFinalPass) {
                        ctx.emit(io.craftpanel.master.service.MigrationEvent.RsyncProgress(false, u.percentComplete, u.bytesTransferred, u.phase))
                    }
                }
        }
        val completeJob = ctx.scope.launch {
            ctx.gateway.agentEvents.filterIsInstance<AgentEvent.RsyncCompleteEvent>()
                .collect { u ->
                    if (u.migrationId == ctx.migrationIdStr && !u.isFinalPass) completeChannel.trySend(u)
                }
        }
        try {
            val sent = ctx.gateway.sendToNode(ctx.sourceNodeIdStr, masterMessage {
                startRsync = startRsyncCommand {
                    migrationId = ctx.migrationIdStr
                    serverId = ctx.serverIdStr
                    destinationIp = ctx.targetPrivateIp
                    destinationPort = ctx.rsyncPort
                    rsyncPassword = ctx.rsyncPassword
                    rsyncImage = ctx.rsyncImage
                    isFinalPass = false
                }
            })
            if (!sent) return StepResult.Failure("Source agent not connected")
            val complete = withTimeoutOrNull(3600.seconds) { completeChannel.receive() }
            if (complete == null || !complete.success) {
                val err = complete?.errorMessage ?: "Timeout waiting for initial rsync"
                return StepResult.Failure("Initial rsync failed: $err")
            }
            ctx.updateStatus(MigrationStatus.SYNCING)
            return StepResult.Success
        }
        finally {
            progressJob.cancel()
            completeJob.cancel()
            completeChannel.close()
        }
    }
}
