package io.craftpanel.master.database.migrations

import io.craftpanel.master.auth.Permission
import io.craftpanel.master.database.schema.GroupPermissions
import io.craftpanel.master.database.schema.Groups
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

private val systemGroups = mapOf(
    "Super Admin" to listOf("*"),
    "Server Admin" to listOf(
        Permission.SERVER_CREATE, Permission.SERVER_DELETE, Permission.SERVER_START, Permission.SERVER_STOP,
        Permission.SERVER_RESTART, Permission.SERVER_CONFIGURE, Permission.SERVER_FILES, Permission.SERVER_MODS,
        Permission.SERVER_CONSOLE, Permission.SERVER_EXPORT, Permission.SERVER_BACKUP, Permission.SERVER_UPGRADE,
        Permission.SERVER_VIEW,
    ).map { it.node },
    "Operator" to listOf(
        Permission.SERVER_RESTART, Permission.SERVER_CONSOLE, Permission.SERVER_VIEW, Permission.SERVER_BACKUP,
    ).map { it.node },
    "Viewer" to listOf(Permission.SERVER_VIEW.node),
)

fun seedSystemGroups() {
    for ((name, permissions) in systemGroups) {
        val exists = Groups.selectAll()
            .where { Groups.name eq name }
            .firstOrNull() != null
        if (exists) continue

        val groupId = Groups.insert {
            it[Groups.name] = name
            it[Groups.isSystem] = true
        }[Groups.id]

        for (perm in permissions) {
            GroupPermissions.insert {
                it[GroupPermissions.groupId] = groupId
                it[GroupPermissions.permission] = perm
            }
        }
    }
}
