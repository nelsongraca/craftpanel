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

class FinalRsyncStep : MigrationStep {

    override val stepNumber = 6
    override val description = "Final rsync pass (delta sync, source stopped)"

    override suspend fun execute(ctx: MigrationContext): StepResult {
        val completeChannel = Channel<AgentEvent.RsyncCompleteEvent>(1)
        val progressJob = ctx.scope.launch {
            ctx.gateway.agentEvents.filterIsInstance<AgentEvent.RsyncProgressEvent>()
                .collect { u ->
                    if (u.migrationId == ctx.migrationIdStr && u.isFinalPass) {
                        ctx.emit(io.craftpanel.master.service.MigrationEvent.RsyncProgress(true, u.percentComplete, u.bytesTransferred, u.phase))
                    }
                }
        }
        val completeJob = ctx.scope.launch {
            ctx.gateway.agentEvents.filterIsInstance<AgentEvent.RsyncCompleteEvent>()
                .collect { u ->
                    if (u.migrationId == ctx.migrationIdStr && u.isFinalPass) completeChannel.trySend(u)
                }
        }
        try {
            ctx.gateway.sendToNode(ctx.sourceNodeIdStr, masterMessage {
                startRsync = startRsyncCommand {
                    migrationId = ctx.migrationIdStr
                    serverId = ctx.serverIdStr
                    destinationIp = ctx.targetPrivateIp
                    destinationPort = ctx.rsyncPort
                    rsyncPassword = ctx.rsyncPassword
                    rsyncImage = ctx.rsyncImage
                    isFinalPass = true
                }
            })
            val complete = withTimeoutOrNull(600.seconds) { completeChannel.receive() }
            if (complete == null || !complete.success) {
                val err = complete?.errorMessage ?: "Timeout waiting for final rsync"
                ctx.restartSource()
                return StepResult.Failure("Final rsync failed: $err")
            }
            ctx.updateStatus(MigrationStatus.CUTTING_OVER)
            return StepResult.Success
        }
        finally {
            progressJob.cancel()
            completeJob.cancel()
            completeChannel.close()
        }
    }
}
