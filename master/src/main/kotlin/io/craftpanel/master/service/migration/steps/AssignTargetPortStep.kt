package io.craftpanel.master.service.migration.steps

import io.craftpanel.master.service.PortExhaustedException
import io.craftpanel.master.service.migration.*

class AssignTargetPortStep : MigrationStep {

    override val stepNumber = 10
    override val description = "Assign host port on target node"

    override suspend fun execute(plan: MigrationPlan, coord: MigrationCoordinator): StepResult {
        return try {
            val existingPort = plan.serverRow.hostPort
            val usedPorts = coord.portRepository.findUsedPortsOnNode(plan.targetNodeId)
                .toSet()

            plan.assignedPort = if (existingPort in usedPorts) {
                val range = plan.targetNodeRow.portRangeStart..plan.targetNodeRow.portRangeEnd
                range.firstOrNull { it !in usedPorts }
                    ?: throw PortExhaustedException("No free ports on target node")
            } else {
                existingPort
            }

            coord.portRepository.releasePortsForServer(plan.serverId)
            coord.portRepository.registerPort(plan.targetNodeId, plan.assignedPort, "TCP", plan.serverId)

            if (plan.assignedPort != existingPort) {
                coord.migrationRepository.updateMigrationHostPort(plan.serverId, plan.assignedPort)
            }

            plan.freshServerRow = coord.serverRepository.findById(plan.serverId)
            if (plan.freshServerRow == null) {
                coord.restartSource(plan)
                return StepResult.Failure("Server row not found after port assignment")
            }
            StepResult.Success
        } catch (e: Exception) {
            coord.restartSource(plan)
            StepResult.Failure("Port assignment failed: ${e.message}")
        }
    }
}
