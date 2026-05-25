package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

object Backups : Table("backups") {
    val id = uuid("id").autoGenerate()
    val serverId = uuid("server_id").references(Servers.id)
    val nodeId = uuid("node_id").references(Nodes.id)
    val status = varchar("status", 10)              // PENDING|RUNNING|SUCCESS|FAILED
    val sizeBytes = long("size_bytes").nullable()
    val storagePath = varchar("storage_path", 500).nullable()
    val startedAt = datetime("started_at").defaultExpression(CurrentDateTime)
    val completedAt = datetime("completed_at").nullable()
    val retainUntil = datetime("retain_until").nullable()   // null = keep forever

    override val primaryKey = PrimaryKey(id)
}
