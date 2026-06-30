package io.craftpanel.master.service.migration.steps

import io.craftpanel.master.service.migration.MigrationContext
import io.craftpanel.master.service.migration.MigrationStep
import io.craftpanel.master.service.migration.StepResult

class UpdateNodeAssignmentStep : MigrationStep {

    override val stepNumber = 11
    override val description = "Update server node assignment in database"

    override suspend fun execute(ctx: MigrationContext): StepResult {
        return try {
            ctx.serverRepository.updateNodeId(ctx.serverId, ctx.targetNodeId)
            ctx.serverRepository.releasePort(ctx.targetNodeId, ctx.rsyncPort, "TCP")
            StepResult.Success
        }
        catch (e: Exception) {
            StepResult.Failure("DB update failed: ${e.message}")
        }
    }
}
