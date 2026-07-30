package io.craftpanel.master.service.migration.steps

import io.craftpanel.master.database.entity.ServerEntity
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.PortRegistry
import io.craftpanel.master.service.migration.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class UpdateNodeAssignmentStep : MigrationStep {

    override val stepNumber = 11
    override val description = "Update server node assignment in database"

    override suspend fun execute(plan: MigrationPlan, coord: MigrationCoordinator): StepResult = try {
        transaction { ServerEntity.findById(plan.serverId)?.let { it.nodeId = EntityID(plan.targetNodeId, Nodes) } }
        transaction { PortRegistry.deleteWhere { (PortRegistry.nodeId eq plan.targetNodeId) and (PortRegistry.port eq plan.rsyncPort) and (PortRegistry.protocol eq "TCP") } }
        StepResult.Success
    } catch (e: Exception) {
        StepResult.Failure("DB update failed: ${e.message}")
    }
}