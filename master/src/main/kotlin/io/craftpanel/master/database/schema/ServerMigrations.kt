package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

object ServerMigrations : Table("server_migrations") {

    val id = uuid("id").autoGenerate()
    val serverId = uuid("server_id").references(Servers.id)
    val sourceNodeId = uuid("source_node_id").references(Nodes.id)
    val targetNodeId = uuid("target_node_id").references(Nodes.id)
    val status = varchar("status", 10)  // PENDING|RUNNING|SUCCESS|FAILED|CANCELLED
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val completedAt = datetime("completed_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
