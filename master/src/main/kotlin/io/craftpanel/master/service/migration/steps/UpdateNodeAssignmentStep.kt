package io.craftpanel.master.service.migration.steps

import io.craftpanel.master.service.migration.*

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
