package io.craftpanel.master.auth

import io.craftpanel.master.database.schema.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

object PermissionResolver {

    fun resolve(userId: Uuid, serverId: Uuid? = null, networkId: Uuid? = null): Set<String> = transaction {
        loadIndex(userId)?.permissionsFor(serverId, networkId) ?: emptySet()
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
        val index = loadIndex(userId) ?: return@transaction emptyMap()

        val servers = Servers.selectAll()
            .map { it[Servers.id].value to it[Servers.networkId]?.value }

        buildMap {
            for ((serverId, networkId) in servers) {
                val granted = index.permissionsFor(serverId, networkId)
                if (granted.any { matches(it, Permission.SERVER_VIEW.node) }) put(serverId, granted)
            }
        }
    }

    /**
     * Resolves, for every network the user can see, the union of permissions granted by their
     * GLOBAL and NETWORK-scoped group assignments. Keyed by network id.
     *
     * Only networks the user can actually view (has [Permission.NETWORK_VIEW] for) are included,
     * mirroring the visibility logic so the frontend can render per-network action buttons.
     */
    fun networkPermissions(userId: Uuid): Map<Uuid, Set<String>> = transaction {
        val index = loadIndex(userId) ?: return@transaction emptyMap()

        val networks = ServerNetworks.selectAll()
            .map { it[ServerNetworks.id].value }

        buildMap {
            for (networkId in networks) {
                val granted = index.permissionsFor(networkId = networkId)
                if (granted.any { matches(it, Permission.NETWORK_VIEW.node) }) put(networkId, granted)
            }
        }
    }

    /**
     * Builds the user's [GrantIndex] from their assignments and their groups' permissions.
     * Returns `null` when the user is missing or inactive. Must run inside a transaction.
     *
     * The scope-union itself lives in [GrantIndex]; this is only the schema-loading adapter, so
     * `PermissionResolver` and the repo-backed visibility resolvers share one algorithm.
     */
    private fun loadIndex(userId: Uuid): GrantIndex? {
        val user = Users.selectAll()
            .where { Users.id eq userId }
            .firstOrNull() ?: return null

        if (!user[Users.isActive]) return null

        val assignments = UserGroupAssignments.selectAll()
            .where { UserGroupAssignments.userId eq userId }
            .map {
                AssignmentScope(
                    groupId = it[UserGroupAssignments.groupId].value,
                    scopeType = it[UserGroupAssignments.scopeType],
                    scopeId = it[UserGroupAssignments.scopeId]
                )
            }

        if (assignments.isEmpty()) return GrantIndex.from(emptyList(), emptyMap())

        val permissionsByGroup = GroupPermissions.selectAll()
            .where { GroupPermissions.groupId inList assignments.map { it.groupId }.toSet() }
            .groupBy({ it[GroupPermissions.groupId].value }, { it[GroupPermissions.permission] })
            .mapValues { it.value.toSet() }

        return GrantIndex.from(assignments, permissionsByGroup)
    }

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
