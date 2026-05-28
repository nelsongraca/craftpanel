package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.Table

object UserGroupAssignments : Table("user_group_assignments") {

    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(Users.id)
    val groupId = uuid("group_id").references(Groups.id)

    // GLOBAL | SERVER | NETWORK
    val scopeType = varchar("scope_type", 10)

    // null for GLOBAL scope; server UUID for SERVER scope; network UUID for NETWORK scope
    val scopeId = uuid("scope_id").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, userId)
        index(false, groupId)
    }
}
