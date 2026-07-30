package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.ReferenceOption

object GroupPermissions : Table("group_permissions") {

    val groupId = reference("group_id", Groups, onDelete = ReferenceOption.CASCADE)
    val permission = varchar("permission", 100)

    override val primaryKey = PrimaryKey(groupId, permission)
}
