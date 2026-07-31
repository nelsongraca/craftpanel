package io.craftpanel.master.service.migration.steps

import io.craftpanel.master.database.entity.Server
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.PortRegistry
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.service.PortExhaustedException
import io.craftpanel.master.service.migration.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

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

            transaction { PortRegistry.deleteWhere { PortRegistry.serverId eq plan.serverId } }
            transaction { PortRegistry.insert { it[PortRegistry.nodeId] = EntityID(plan.targetNodeId, Nodes); it[PortRegistry.port] = plan.assignedPort; it[PortRegistry.protocol] = "TCP"; it[PortRegistry.serverId] = EntityID(plan.serverId, Servers) } }

            if (plan.assignedPort != existingPort) {
                transaction { Server.findById(plan.serverId)?.let { it.hostPort = plan.assignedPort } }
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