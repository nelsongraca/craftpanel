package io.craftpanel.master.service.migration.steps

import io.craftpanel.master.service.migration.*

class RemoveSourceContainerStep : MigrationStep {

    override val stepNumber = 7
    override val description = "Remove source server container"

    override suspend fun execute(plan: MigrationPlan, coord: MigrationCoordinator): StepResult = runCatching {
        coord.lifecycle.remove(plan.serverRow, plan.sourceNodeIdStr)
        plan.sourceStopped = false
        StepResult.Success
    }.getOrElse { e ->
        coord.restartSource(plan)
        StepResult.Failure("Source container removal failed: ${e.message}")
    }
}
