package io.craftpanel.master.service.migration.steps

import io.craftpanel.master.service.migration.MigrationCoordinator
import io.craftpanel.master.service.migration.MigrationPlan
import io.craftpanel.master.service.migration.MigrationStep
import io.craftpanel.master.service.migration.StepResult

class StartTargetContainerStep : MigrationStep {

    override val stepNumber = 8
    override val description = "Create and start server container on target node"

    override suspend fun execute(plan: MigrationPlan, coord: MigrationCoordinator): StepResult {
        val server = plan.freshServerRow ?: return StepResult.Failure("Server row not available")
        return runCatching {
            coord.lifecycle.start(server, needsRecreate = true, nodeId = plan.targetNodeIdStr)
            StepResult.Success
        }.getOrElse { e ->
            runCatching { coord.lifecycle.remove(server, plan.targetNodeIdStr, force = true) }
            StepResult.Failure("New container failed to start on target: ${e.message}")
        }
    }
}
