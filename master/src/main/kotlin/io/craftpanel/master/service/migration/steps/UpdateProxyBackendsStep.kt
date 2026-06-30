package io.craftpanel.master.service.migration.steps

import io.craftpanel.master.service.migration.MigrationContext
import io.craftpanel.master.service.migration.MigrationStep
import io.craftpanel.master.service.migration.StepResult

class UpdateProxyBackendsStep : MigrationStep {

    override val stepNumber = 12
    override val description = "Update proxy backends"

    override suspend fun execute(ctx: MigrationContext): StepResult {
        val freshServer = ctx.freshServerRow ?: return StepResult.Failure("Server row not available")
        runCatching {
            ctx.updateProxyBackendsAfterMigration(ctx.serverId, ctx.targetNodeRow.privateIp, freshServer.hostPort)
        }
        return StepResult.Success
    }
}
