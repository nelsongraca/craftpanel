package io.craftpanel.master.service

import io.craftpanel.master.auth.*
import io.craftpanel.master.service.repo.GroupRepository
import io.craftpanel.master.service.repo.UserRepository
import kotlin.uuid.Uuid

internal data class NetworkVisibility(val isGlobal: Boolean, val networkIds: Set<Uuid>)

/**
 * Resolves which Server Networks a user can view, based on groups carrying [Permission.NETWORK_VIEW]
 * (GLOBAL → all, NETWORK-scoped → those networks, else empty). Mirrors [ServerVisibilityResolver]
 * but is keyed on network.view instead of server.view.
 */
class NetworkVisibilityResolver(
    private val userRepository: UserRepository,
    private val groupRepository: GroupRepository
) {

    internal fun resolve(userId: Uuid): NetworkVisibility {
        if (!userRepository.isActive(userId)) return NetworkVisibility(false, emptySet())
        val assignments = userRepository.listAssignments(userId)
        val groupIds = assignments.map { it.groupId }.toSet()
        if (groupIds.isEmpty()) return NetworkVisibility(false, emptySet())
        val viewGroups = groupIds.filter { gid ->
            groupRepository.getPermissions(gid)
                .any { PermissionResolver.grants(it, Permission.NETWORK_VIEW) }
        }.toSet()
        if (viewGroups.isEmpty()) return NetworkVisibility(false, emptySet())
        var isGlobal = false
        val networkIds = mutableSetOf<Uuid>()
        for (a in assignments.filter { it.groupId in viewGroups }) {
            when (a.scopeType) {
                ScopeType.GLOBAL.name -> isGlobal = true
                ScopeType.NETWORK.name -> a.scopeId?.let { networkIds += it }
                ScopeType.SERVER.name -> Unit
            }
        }
        return NetworkVisibility(isGlobal, networkIds)
    }
}
