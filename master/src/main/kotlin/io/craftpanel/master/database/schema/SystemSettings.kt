package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

object SystemSettings : Table("system_settings") {

    val key = varchar("key", 100)
    val value = text("value")
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
    val updatedBy = reference("updated_by", Users, onDelete = ReferenceOption.CASCADE)
        .nullable()

    override val primaryKey = PrimaryKey(key)
}
