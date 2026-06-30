package io.craftpanel.master.service.migration.steps

import io.craftpanel.master.service.PortExhaustedException
import io.craftpanel.master.service.migration.MigrationContext
import io.craftpanel.master.service.migration.MigrationStep
import io.craftpanel.master.service.migration.StepResult

class AssignTargetPortStep : MigrationStep {

    override val stepNumber = 10
    override val description = "Assign host port on target node"

    override suspend fun execute(ctx: MigrationContext): StepResult {
        return try {
            val existingPort = ctx.serverRow.hostPort
            val usedPorts = ctx.serverRepository.findUsedPortsOnNode(ctx.targetNodeId)
                .toSet()

            ctx.assignedPort = if (existingPort in usedPorts) {
                val range = ctx.targetNodeRow.portRangeStart..ctx.targetNodeRow.portRangeEnd
                range.firstOrNull { it !in usedPorts }
                    ?: throw PortExhaustedException("No free ports on target node")
            }
            else existingPort

            ctx.serverRepository.releasePortsForServer(ctx.serverId)
            ctx.serverRepository.registerPort(ctx.targetNodeId, ctx.assignedPort, "TCP", ctx.serverId)

            if (ctx.assignedPort != existingPort) {
                ctx.serverRepository.updateMigrationHostPort(ctx.serverId, ctx.assignedPort)
            }

            ctx.freshServerRow = ctx.serverRepository.findById(ctx.serverId)
            if (ctx.freshServerRow == null) {
                ctx.restartSource()
                return StepResult.Failure("Server row not found after port assignment")
            }
            StepResult.Success
        }
        catch (e: Exception) {
            ctx.restartSource()
            StepResult.Failure("Port assignment failed: ${e.message}")
        }
    }
}
