package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

object ServerMigrations : UuidTable("server_migrations") {

    val serverId = reference("server_id", Servers, onDelete = ReferenceOption.CASCADE)
    val sourceNodeId = reference("source_node_id", Nodes, onDelete = ReferenceOption.CASCADE)
    val targetNodeId = reference("target_node_id", Nodes, onDelete = ReferenceOption.CASCADE)
    val status = varchar("status", 15) // PENDING|SYNCING|CUTTING_OVER|COMPLETED|FAILED|CANCELLED
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val completedAt = datetime("completed_at").nullable()
}
