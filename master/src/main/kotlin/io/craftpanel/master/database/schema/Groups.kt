package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

object Groups : Table("groups") {

    val id = uuid("id").autoGenerate()
    val name = varchar("name", 100).uniqueIndex()
    val isSystem = bool("is_system").default(false)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}
