package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

object Groups : UuidTable("groups") {

    val name = varchar("name", 100).uniqueIndex()
    val isSystem = bool("is_system").default(false)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
}
