package io.craftpanel.master.service

import io.craftpanel.master.auth.*
import io.craftpanel.master.service.repo.GroupRepository
import io.craftpanel.master.service.repo.UserRepository
import kotlin.uuid.Uuid

internal data class NetworkVisibility(val isGlobal: Boolean, val networkIds: Set<Uuid>)

/**
 * Resolves which Server Networks a user can view, based on groups carrying [Permission.NETWORK_VIEW]
 * (GLOBAL → all, NETWORK-scoped → those networks, else empty). Mirrors [ServerVisibilityResolver]
 * but is keyed on network.view instead of server.view; both share [GrantIndex]'s scope union.
 */
class NetworkVisibilityResolver(private val userRepository: UserRepository, private val groupRepository: GroupRepository) {

    internal fun resolve(userId: Uuid): NetworkVisibility {
        if (!userRepository.isActive(userId)) return NetworkVisibility(false, emptySet())
        val assignments = userRepository.listAssignments(userId)
        if (assignments.isEmpty()) return NetworkVisibility(false, emptySet())

        val permissionsByGroup = assignments.map { it.groupId }
            .toSet()
            .associateWith { groupRepository.getPermissions(it).toSet() }
        val scopes = GrantIndex.from(
            assignments.map { AssignmentScope(it.groupId, it.scopeType, it.scopeId) },
            permissionsByGroup
        ).scopesGranting(Permission.NETWORK_VIEW)

        return NetworkVisibility(scopes.global, scopes.networkIds)
    }
}
