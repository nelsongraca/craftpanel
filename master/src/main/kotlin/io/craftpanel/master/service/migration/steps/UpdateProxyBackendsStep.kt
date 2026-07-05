package io.craftpanel.master.service.migration.steps

import io.craftpanel.master.service.migration.MigrationCoordinator
import io.craftpanel.master.service.migration.MigrationPlan
import io.craftpanel.master.service.migration.MigrationStep
import io.craftpanel.master.service.migration.StepResult

class UpdateProxyBackendsStep : MigrationStep {

    override val stepNumber = 12
    override val description = "Update proxy backends"

    override suspend fun execute(plan: MigrationPlan, coord: MigrationCoordinator): StepResult {
        val freshServer = plan.freshServerRow ?: return StepResult.Failure("Server row not available")
        runCatching {
            coord.updateProxyBackendsAfterMigration(plan.serverId, plan.targetNodeRow.privateIp, freshServer.hostPort)
        }
        return StepResult.Success
    }
}
