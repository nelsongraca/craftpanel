package io.craftpanel.master.service.migration.steps

import io.craftpanel.master.database.entity.Server
import io.craftpanel.master.database.entity.ServerExtraPort
import io.craftpanel.master.database.schema.ServerExtraPorts
import io.craftpanel.master.service.ServerPortRegistry
import io.craftpanel.master.service.migration.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class AssignTargetPortStep : MigrationStep {

    override val stepNumber = 10
    override val description = "Assign host port on target node"

    override suspend fun execute(plan: MigrationPlan, coord: MigrationCoordinator): StepResult {
        return try {
            val existingPort = plan.serverRow.hostPort
            // Extra ports move with the server and keep their host ports, so they are already
            // spoken for on the target even before they are registered there. Reserving them keeps
            // the primary allocation from picking one and colliding on the registry primary key.
            val extraPorts = transaction {
                ServerExtraPort.find { ServerExtraPorts.serverId eq plan.serverId }.toList()
            }
            plan.assignedPort = coord.portAllocator.allocate(
                plan.targetNodeId,
                preferred = existingPort,
                reserved = extraPorts.map { it.hostPort }.toSet()
            )

            transaction {
                // Repoint every row this server owns at the target node: the primary port in its
                // configured protocol(s) and every extra port (their host ports stay unchanged —
                // the target spec rebinds them as-is). Extra ports used to be dropped here, leaving
                // their ports free for another server to claim after a migration.
                ServerPortRegistry.unregisterAll(plan.serverId)
                ServerPortRegistry.register(
                    plan.targetNodeId,
                    plan.serverId,
                    plan.assignedPort,
                    plan.serverRow.containerProtocol
                )
                extraPorts.forEach { extra ->
                    ServerPortRegistry.register(plan.targetNodeId, plan.serverId, extra.hostPort, extra.protocol)
                }

                if (plan.assignedPort != existingPort) {
                    Server.findById(plan.serverId)?.let { it.hostPort = plan.assignedPort }
                }
            }

            plan.freshServerView = coord.serverRepository.findById(plan.serverId)
            if (plan.freshServerView == null) {
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
