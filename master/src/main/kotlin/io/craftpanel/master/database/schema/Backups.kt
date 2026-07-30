package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

object Backups : Table("backups") {

    val id = uuid("id").autoGenerate()
    val serverId = reference("server_id", Servers, onDelete = ReferenceOption.CASCADE)
    val nodeId = uuid("node_id").references(Nodes.id)
    val trigger = varchar("trigger", 10) // MANUAL|SCHEDULED
    val status = varchar("status", 15) // IN_PROGRESS|COMPLETED|FAILED
    val filePath = varchar("file_path", 500).nullable()
    val sizeBytes = long("size_bytes").nullable()
    val errorMessage = text("error_message").nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val completedAt = datetime("completed_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
