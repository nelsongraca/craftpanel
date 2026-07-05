package io.craftpanel.master.service.migration.steps

import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.service.migration.MigrationCoordinator
import io.craftpanel.master.service.migration.MigrationPlan
import io.craftpanel.master.service.migration.MigrationStep
import io.craftpanel.master.service.migration.StepResult
import io.craftpanel.proto.masterMessage
import io.craftpanel.proto.prepareRsyncReceiveCommand
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

class PrepareRsyncReceiveStep : MigrationStep {

    override val stepNumber = 2
    override val description = "Prepare rsync receiver on target node"

    override suspend fun execute(plan: MigrationPlan, coord: MigrationCoordinator): StepResult {
        val readyChannel = Channel<AgentEvent.RsyncReadyEvent>(1)
        val job = coord.scope.launch {
            coord.gateway.agentEvents.filterIsInstance<AgentEvent.RsyncReadyEvent>()
                .collect { if (it.migrationId == plan.migrationIdStr) readyChannel.trySend(it) }
        }
        try {
            val sent = coord.gateway.sendToNode(
                plan.targetNodeIdStr,
                masterMessage {
                    prepareRsyncReceive = prepareRsyncReceiveCommand {
                        migrationId = plan.migrationIdStr
                        serverId = plan.serverIdStr
                        port = plan.rsyncPort
                        rsyncImage = plan.rsyncImage
                    }
                }
            )
            if (!sent) return StepResult.Failure("Target agent not connected")
            val ready = withTimeoutOrNull(60.seconds) { readyChannel.receive() }
            if (ready == null) return StepResult.Failure("Timeout waiting for rsync receiver on target")
            plan.rsyncPassword = ready.rsyncPassword
            return StepResult.Success
        } finally {
            job.cancel()
            readyChannel.close()
        }
    }
}
