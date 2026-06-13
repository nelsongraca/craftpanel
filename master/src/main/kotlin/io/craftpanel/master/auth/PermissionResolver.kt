package io.craftpanel.master.auth

import io.craftpanel.master.database.schema.GroupPermissions
import io.craftpanel.master.database.schema.UserGroupAssignments
import io.craftpanel.master.database.schema.Users
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

object PermissionResolver {

    fun resolve(userId: Uuid, serverId: Uuid? = null, networkId: Uuid? = null): Set<String> =
        transaction {

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

    fun hasPermission(
        userId: Uuid,
        permission: Permission,
        serverId: Uuid? = null,
        networkId: Uuid? = null,
    ): Boolean {
        val granted = resolve(userId, serverId, networkId)
        return granted.any { matches(it, permission.node) }
    }

    private fun groupIdsForScope(userId: Uuid, scopeType: String, scopeId: Uuid?): List<Uuid> =
        UserGroupAssignments.selectAll()
            .where {
                val base = (UserGroupAssignments.userId eq userId) and
                        (UserGroupAssignments.scopeType eq scopeType)
                if (scopeId != null) base and (UserGroupAssignments.scopeId eq scopeId)
                else base
            }
            .map { it[UserGroupAssignments.groupId] }

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
