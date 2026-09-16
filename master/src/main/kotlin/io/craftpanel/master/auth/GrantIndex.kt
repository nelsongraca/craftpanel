package io.craftpanel.master.auth

import kotlin.uuid.Uuid

/** A group assignment at a scope, as a plain value ([scopeType] is a [ScopeType] name). */
data class AssignmentScope(val groupId: Uuid, val scopeType: String, val scopeId: Uuid?)

/** The scopes whose groups grant a permission: a global flag, plus the server/network ids. */
data class ScopeSet(val global: Boolean, val serverIds: Set<Uuid>, val networkIds: Set<Uuid>) {
    val isEmpty: Boolean get() = !global && serverIds.isEmpty() && networkIds.isEmpty()
}

/**
 * The one scope-union algorithm. Given a user's group assignments plus their groups' permission
 * nodes, it answers "what does this user hold at this scope?" and "which scopes grant this
 * permission?". Pure and immutable — build it with [from], or construct it directly in tests.
 *
 * Every scope may carry several groups (a user can hold two SERVER-scoped groups on the same
 * server); the union is over all of them.
 *
 * Callers with different data paths share this: `PermissionResolver` builds it from schema queries,
 * the visibility resolvers build it from `UserRepository`/`GroupRepository`.
 */
data class GrantIndex(val globalGroupIds: Set<Uuid>, val serverGroupIds: Map<Uuid, Set<Uuid>>, val networkGroupIds: Map<Uuid, Set<Uuid>>, val groupPermissions: Map<Uuid, Set<String>>) {

    /** Union of the permission nodes granted by every group applicable at (serverId, networkId). */
    fun permissionsFor(serverId: Uuid? = null, networkId: Uuid? = null): Set<String> = buildSet {
        globalGroupIds.forEach { addAll(groupPermissions[it].orEmpty()) }
        serverId?.let { sid ->
            serverGroupIds[sid]?.forEach { addAll(groupPermissions[it].orEmpty()) }
        }
        networkId?.let { nid ->
            networkGroupIds[nid]?.forEach { addAll(groupPermissions[it].orEmpty()) }
        }
    }

    /** The scopes at which any assigned group grants [permission]. */
    fun scopesGranting(permission: Permission): ScopeSet = ScopeSet(
        global = globalGroupIds.any { grants(it, permission) },
        serverIds = serverGroupIds.filterValues { groups -> groups.any { grants(it, permission) } }.keys,
        networkIds = networkGroupIds.filterValues { groups -> groups.any { grants(it, permission) } }.keys
    )

    private fun grants(groupId: Uuid, permission: Permission): Boolean = groupPermissions[groupId].orEmpty().any { PermissionResolver.grants(it, permission) }

    companion object {

        /** Index [assignments] by scope, carrying [permissionsByGroup] (groupId -> nodes). */
        fun from(assignments: List<AssignmentScope>, permissionsByGroup: Map<Uuid, Set<String>>): GrantIndex {
            val global = mutableSetOf<Uuid>()
            val byServer = mutableMapOf<Uuid, MutableSet<Uuid>>()
            val byNetwork = mutableMapOf<Uuid, MutableSet<Uuid>>()
            for (assignment in assignments) {
                when (assignment.scopeType) {
                    ScopeType.GLOBAL.name -> global += assignment.groupId
                    ScopeType.SERVER.name -> assignment.scopeId?.let { byServer.getOrPut(it) { mutableSetOf() } += assignment.groupId }
                    ScopeType.NETWORK.name -> assignment.scopeId?.let { byNetwork.getOrPut(it) { mutableSetOf() } += assignment.groupId }
                }
            }
            return GrantIndex(global, byServer, byNetwork, permissionsByGroup)
        }
    }
}
