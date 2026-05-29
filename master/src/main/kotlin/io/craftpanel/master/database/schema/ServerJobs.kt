package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

object ServerJobs : Table("server_jobs") {

    val id = uuid("id").autoGenerate()
    val serverId = uuid("server_id").references(Servers.id)
    val type = varchar("type", 50)
    val cronExpression = varchar("cron_expression", 64)
    val enabled = bool("enabled").default(true)
    val lastFiredAt = datetime("last_fired_at").nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}
