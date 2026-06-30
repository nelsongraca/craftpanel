package io.craftpanel.master.service.migration.steps

import io.craftpanel.master.service.migration.MigrationContext
import io.craftpanel.master.service.migration.MigrationStep
import io.craftpanel.master.service.migration.StepResult

class StopSourceStep : MigrationStep {

    override val stepNumber = 5
    override val description = "Stop source server container"

    override suspend fun execute(ctx: MigrationContext): StepResult {
        val alreadyStopped = ctx.serverRow.status == "STOPPED"
        if (!alreadyStopped) {
            return runCatching {
                ctx.lifecycle.stop(ctx.serverRow, ctx.sourceNodeIdStr)
                ctx.sourceStopped = true
                StepResult.Success
            }.getOrElse { e ->
                StepResult.Failure("Source server did not stop: ${e.message}")
            }
        }
        ctx.sourceStopped = true
        return StepResult.Success
    }
}
