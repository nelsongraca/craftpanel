package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

object ServerJobs : UuidTable("server_jobs") {

    val serverId = reference("server_id", Servers, onDelete = ReferenceOption.CASCADE)
    val type = varchar("type", 50)
    val cronExpression = varchar("cron_expression", 64)
    val enabled = bool("enabled").default(true)
    val lastFiredAt = datetime("last_fired_at").nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
}
