package io.craftpanel.master.service.migration.steps

import io.craftpanel.master.service.migration.MigrationContext
import io.craftpanel.master.service.migration.MigrationStep
import io.craftpanel.master.service.migration.StepResult

class AllocateRsyncPortStep : MigrationStep {

    override val stepNumber = 1
    override val description = "Allocate rsync port on target node"

    override suspend fun execute(ctx: MigrationContext): StepResult {
        return try {
            ctx.rsyncPort = ctx.allocateRsyncPort()
            StepResult.Success
        }
        catch (e: Exception) {
            StepResult.Failure("Port allocation failed: ${e.message}")
        }
    }
}
