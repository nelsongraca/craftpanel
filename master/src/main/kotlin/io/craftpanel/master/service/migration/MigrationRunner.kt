package io.craftpanel.master.service.migration

import io.craftpanel.master.domain.MigrationStatus
import io.craftpanel.master.service.MigrationEvent
import io.craftpanel.proto.masterMessage
import io.craftpanel.proto.removeContainerCommand
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

class MigrationRunner(private val steps: List<MigrationStep>, private val plan: MigrationPlan, private val coord: MigrationCoordinator) {

    suspend fun run() {
        try {
            for (step in steps) {
                val stepId = coord.startStep(plan, step.stepNumber, step.description)
                val result = step.execute(plan, coord)
                when (result) {
                    is StepResult.Success -> coord.completeStep(stepId, true)

                    is StepResult.Failure -> {
                        coord.completeStep(stepId, false, result.error)
                        coord.failMigration(plan, result.error)
                        return
                    }
                }
                if (step.stepNumber == 4) delay(2.seconds)
            }
            coord.updateStatus(plan, MigrationStatus.COMPLETED)
            coord.emit(MigrationEvent.Completed)
        } finally {
            runCatching {
                coord.gateway.sendToNode(
                    plan.targetNodeIdStr,
                    masterMessage {
                        removeContainer = removeContainerCommand {
                            containerName = "${plan.containerNamePrefix}-rsync-recv-${plan.migrationIdStr}"
                            force = true
                        }
                    }
                )
            }
            coord.serverRepository.releasePort(plan.targetNodeId, plan.rsyncPort, "TCP")
        }
    }
}
