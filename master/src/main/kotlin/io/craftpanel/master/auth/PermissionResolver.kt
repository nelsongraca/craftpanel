package io.craftpanel.master.auth

import io.craftpanel.master.database.schema.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

object PermissionResolver {

    fun resolve(userId: Uuid, serverId: Uuid? = null, networkId: Uuid? = null): Set<String> = transaction {
        val user = Users.selectAll()
            .where { Users.id eq userId }
            .firstOrNull() ?: return@transaction emptySet()

        if (!user[Users.isActive]) return@transaction emptySet()

        val groupIds = buildList {
            addAll(groupIdsForScope(userId, ScopeType.GLOBAL.name, null))
            if (serverId != null) addAll(groupIdsForScope(userId, ScopeType.SERVER.name, serverId))
            if (networkId != null) addAll(groupIdsForScope(userId, ScopeType.NETWORK.name, networkId))
        }.toSet()

        if (groupIds.isEmpty()) return@transaction emptySet()

        GroupPermissions.selectAll()
            .where { GroupPermissions.groupId inList groupIds }
            .map { it[GroupPermissions.permission] }
            .toSet()
    }

    fun hasPermission(userId: Uuid, permission: Permission, serverId: Uuid? = null, networkId: Uuid? = null): Boolean {
        val granted = resolve(userId, serverId, networkId)
        return granted.any { matches(it, permission.node) }
    }

    fun grants(granted: String, permission: Permission): Boolean = matches(granted, permission.node)

    /**
     * Resolves, for every server the user can see, the union of permissions granted by their
     * GLOBAL, SERVER-scoped, and NETWORK-scoped group assignments. Keyed by server id.
     *
     * Only servers the user can actually view (has [Permission.SERVER_VIEW] for) are included,
     * mirroring the visibility logic so the frontend can render per-server action buttons.
     */
    fun serverPermissions(userId: Uuid): Map<Uuid, Set<String>> = transaction {
        val user = Users.selectAll()
            .where { Users.id eq userId }
            .firstOrNull() ?: return@transaction emptyMap()

        if (!user[Users.isActive]) return@transaction emptyMap()

        // (id, networkId) for every server.
        val servers = Servers.selectAll()
            .map { row ->
                (row[Servers.id].value to row[Servers.networkId]?.value)
            }

        val assignments = UserGroupAssignments.selectAll()
            .where { UserGroupAssignments.userId eq userId }
            .map { Assignment(it[UserGroupAssignments.groupId].value, it[UserGroupAssignments.scopeType], it[UserGroupAssignments.scopeId]) }

        if (assignments.isEmpty()) return@transaction emptyMap()

        // groupId -> permission nodes
        val groupPermissions = GroupPermissions.selectAll()
            .where {
                GroupPermissions.groupId inList assignments.map { it.groupId }
                    .toSet()
            }
            .groupBy({ it[GroupPermissions.groupId].value }, { it[GroupPermissions.permission] })

        val globalGroupIds = assignments.filter { it.scopeType == ScopeType.GLOBAL.name }
            .map { it.groupId }
            .toSet()
        val serverGroupIds = assignments.filter { it.scopeType == ScopeType.SERVER.name }
            .associate { it.scopeId to it.groupId }
        val networkGroupIds = assignments.filter { it.scopeType == ScopeType.NETWORK.name }
            .associate { it.scopeId to it.groupId }

        val result = mutableMapOf<Uuid, Set<String>>()
        for ((serverId, networkId) in servers) {
            val granted = buildSet {
                globalGroupIds.forEach { addAll(groupPermissions[it].orEmpty()) }
                serverGroupIds[serverId]?.let { addAll(groupPermissions[it].orEmpty()) }
                networkId?.let { nid -> networkGroupIds[nid]?.let { addAll(groupPermissions[it].orEmpty()) } }
            }
            // Only surface servers the user can actually view.
            if (granted.any { matches(it, Permission.SERVER_VIEW.node) }) {
                result[serverId] = granted
            }
        }
        result
    }

    private data class Assignment(val groupId: Uuid, val scopeType: String, val scopeId: Uuid?)

    private fun groupIdsForScope(userId: Uuid, scopeType: String, scopeId: Uuid?): List<Uuid> = UserGroupAssignments.selectAll()
        .where {
            val base = (UserGroupAssignments.userId eq userId) and
                (UserGroupAssignments.scopeType eq scopeType)
            if (scopeId != null) {
                base and (UserGroupAssignments.scopeId eq scopeId)
            }
            else {
                base
            }
        }
        .map { it[UserGroupAssignments.groupId].value }

    private fun matches(granted: String, required: String): Boolean {
        if (granted == "*") return true
        if (granted == required) return true
        if (granted.endsWith(".*")) {
            val prefix = granted.dropLast(2)
            return required.startsWith("$prefix.")
        }
        return false
    }
}
