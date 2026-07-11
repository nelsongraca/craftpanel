package io.craftpanel.master.service.migration.steps

import io.craftpanel.master.service.migration.MigrationCoordinator
import io.craftpanel.master.service.migration.MigrationPlan
import io.craftpanel.master.service.migration.MigrationStep
import io.craftpanel.master.service.migration.StepResult

class UpdateNodeAssignmentStep : MigrationStep {

    override val stepNumber = 11
    override val description = "Update server node assignment in database"

    override suspend fun execute(plan: MigrationPlan, coord: MigrationCoordinator): StepResult = try {
        coord.migrationRepository.updateNodeId(plan.serverId, plan.targetNodeId)
        coord.portRepository.releasePort(plan.targetNodeId, plan.rsyncPort, "TCP")
        StepResult.Success
    } catch (e: Exception) {
        StepResult.Failure("DB update failed: ${e.message}")
    }
}
