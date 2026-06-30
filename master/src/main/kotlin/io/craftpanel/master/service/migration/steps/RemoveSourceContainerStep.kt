package io.craftpanel.master.service.migration.steps

import io.craftpanel.master.service.migration.MigrationContext
import io.craftpanel.master.service.migration.MigrationStep
import io.craftpanel.master.service.migration.StepResult

class RemoveSourceContainerStep : MigrationStep {

    override val stepNumber = 7
    override val description = "Remove source server container"

    override suspend fun execute(ctx: MigrationContext): StepResult {
        return runCatching {
            ctx.lifecycle.remove(ctx.serverRow, ctx.sourceNodeIdStr)
            ctx.sourceStopped = false
            StepResult.Success
        }.getOrElse { e ->
            ctx.restartSource()
            StepResult.Failure("Source container removal failed: ${e.message}")
        }
    }
}
