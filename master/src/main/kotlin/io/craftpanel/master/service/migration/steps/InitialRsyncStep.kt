package io.craftpanel.master.service.migration.steps

import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.domain.MigrationStatus
import io.craftpanel.master.service.migration.*
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

    override suspend fun execute(plan: MigrationPlan, coord: MigrationCoordinator): StepResult {
        val completeChannel = Channel<AgentEvent.RsyncCompleteEvent>(1)
        val progressJob = coord.scope.launch {
            coord.gateway.agentEvents.filterIsInstance<AgentEvent.RsyncProgressEvent>()
                .collect { u ->
                    if (u.migrationId == plan.migrationIdStr && !u.isFinalPass) {
                        coord.emit(io.craftpanel.master.service.MigrationEvent.RsyncProgress(false, u.percentComplete, u.bytesTransferred, u.phase))
                    }
                }
        }
        val completeJob = coord.scope.launch {
            coord.gateway.agentEvents.filterIsInstance<AgentEvent.RsyncCompleteEvent>()
                .collect { u ->
                    if (u.migrationId == plan.migrationIdStr && !u.isFinalPass) completeChannel.trySend(u)
                }
        }
        try {
            val sent = coord.gateway.sendToNode(
                plan.sourceNodeIdStr,
                masterMessage {
                    startRsync = startRsyncCommand {
                        migrationId = plan.migrationIdStr
                        serverId = plan.serverIdStr
                        destinationIp = plan.targetPrivateIp
                        destinationPort = plan.rsyncPort
                        rsyncPassword = plan.rsyncPassword
                        rsyncImage = plan.rsyncImage
                        isFinalPass = false
                    }
                }
            )
            if (!sent) return StepResult.Failure("Source agent not connected")
            val complete = withTimeoutOrNull(3600.seconds) { completeChannel.receive() }
            if (complete == null || !complete.success) {
                val err = complete?.errorMessage ?: "Timeout waiting for initial rsync"
                return StepResult.Failure("Initial rsync failed: $err")
            }
            coord.updateStatus(plan, MigrationStatus.SYNCING)
            return StepResult.Success
        } finally {
            progressJob.cancel()
            completeJob.cancel()
            completeChannel.close()
        }
    }
}
