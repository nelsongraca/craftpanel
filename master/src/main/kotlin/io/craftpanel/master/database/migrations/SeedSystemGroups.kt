package io.craftpanel.master.database.migrations

import io.craftpanel.master.database.schema.GroupPermissions
import io.craftpanel.master.database.schema.Groups
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

private val systemGroups = mapOf(
    "Super Admin" to listOf("*"),
    "Server Admin" to listOf(
        "server.create", "server.delete", "server.start", "server.stop",
        "server.restart", "server.configure", "server.files", "server.mods",
        "server.console", "server.export", "server.backup", "server.upgrade",
        "server.view",
    ),
    "Operator" to listOf(
        "server.restart", "server.console", "server.view", "server.backup",
    ),
    "Viewer" to listOf("server.view"),
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
