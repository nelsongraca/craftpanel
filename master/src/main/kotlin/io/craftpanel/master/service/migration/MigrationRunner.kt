package io.craftpanel.master.service.migration

import io.craftpanel.master.database.schema.PortRegistry
import io.craftpanel.master.domain.MigrationStatus
import io.craftpanel.master.service.MigrationEvent
import io.craftpanel.proto.masterMessage
import io.craftpanel.proto.removeContainerCommand
import kotlinx.coroutines.delay
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.seconds

class MigrationRunner(private val steps: List<MigrationStep>, private val plan: MigrationPlan, private val coord: MigrationCoordinator) {

    suspend fun run() {
        try {
            for (step in steps) {
                val stepId = coord.startStep(plan, step.stepNumber, step.description)
                when (val result = step.execute(plan, coord)) {
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
            transaction { PortRegistry.deleteWhere { (PortRegistry.nodeId eq plan.targetNodeId) and (PortRegistry.port eq plan.rsyncPort) and (PortRegistry.protocol eq "TCP") } }
        }
    }
}