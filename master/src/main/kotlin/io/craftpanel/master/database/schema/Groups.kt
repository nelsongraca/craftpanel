package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.Table

object Groups : Table("groups") {
    val id = uuid("id").autoGenerate()
    val name = varchar("name", 100).uniqueIndex()
    val isSystem = bool("is_system").default(false)

    override val primaryKey = PrimaryKey(id)
}
