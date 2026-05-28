package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.Table

object GroupPermissions : Table("group_permissions") {

    val groupId = uuid("group_id").references(Groups.id)
    val permission = varchar("permission", 100)

    override val primaryKey = PrimaryKey(groupId, permission)
}
