package io.craftpanel.master.service.migration.steps

import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.domain.MigrationStatus
import io.craftpanel.master.service.migration.MigrationCoordinator
import io.craftpanel.master.service.migration.MigrationPlan
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

    override suspend fun execute(plan: MigrationPlan, coord: MigrationCoordinator): StepResult {
        val completeChannel = Channel<AgentEvent.RsyncCompleteEvent>(1)
        val progressJob = coord.scope.launch {
            coord.gateway.agentEvents.filterIsInstance<AgentEvent.RsyncProgressEvent>()
                .collect { u ->
                    if (u.migrationId == plan.migrationIdStr && u.isFinalPass) {
                        coord.emit(io.craftpanel.master.service.MigrationEvent.RsyncProgress(true, u.percentComplete, u.bytesTransferred, u.phase))
                    }
                }
        }
        val completeJob = coord.scope.launch {
            coord.gateway.agentEvents.filterIsInstance<AgentEvent.RsyncCompleteEvent>()
                .collect { u ->
                    if (u.migrationId == plan.migrationIdStr && u.isFinalPass) completeChannel.trySend(u)
                }
        }
        try {
            coord.gateway.sendToNode(
                plan.sourceNodeIdStr,
                masterMessage {
                    startRsync = startRsyncCommand {
                        migrationId = plan.migrationIdStr
                        serverId = plan.serverIdStr
                        destinationIp = plan.targetPrivateIp
                        destinationPort = plan.rsyncPort
                        rsyncPassword = plan.rsyncPassword
                        rsyncImage = plan.rsyncImage
                        isFinalPass = true
                    }
                }
            )
            val complete = withTimeoutOrNull(600.seconds) { completeChannel.receive() }
            if (complete == null || !complete.success) {
                val err = complete?.errorMessage ?: "Timeout waiting for final rsync"
                coord.restartSource(plan)
                return StepResult.Failure("Final rsync failed: $err")
            }
            coord.updateStatus(plan, MigrationStatus.CUTTING_OVER)
            return StepResult.Success
        } finally {
            progressJob.cancel()
            completeJob.cancel()
            completeChannel.close()
        }
    }
}
