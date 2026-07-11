package io.craftpanel.master.service.migration.steps

import io.craftpanel.master.service.migration.*

class StopSourceStep : MigrationStep {

    override val stepNumber = 5
    override val description = "Stop source server container"

    override suspend fun execute(plan: MigrationPlan, coord: MigrationCoordinator): StepResult {
        val alreadyStopped = plan.serverRow.status == "STOPPED"
        if (!alreadyStopped) {
            return runCatching {
                coord.lifecycle.stop(plan.serverRow, plan.sourceNodeIdStr)
                plan.sourceStopped = true
                StepResult.Success
            }.getOrElse { e ->
                StepResult.Failure("Source server did not stop: ${e.message}")
            }
        }
        plan.sourceStopped = true
        return StepResult.Success
    }
}
