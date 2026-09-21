package io.craftpanel.master.service

import io.craftpanel.master.auth.Permission
import io.craftpanel.master.service.repo.GroupRepository
import io.craftpanel.master.service.repo.UserRepository
import kotlin.uuid.Uuid

internal data class NetworkVisibility(val isGlobal: Boolean, val networkIds: Set<Uuid>)

/**
 * Resolves which Server Networks a user can view, based on groups carrying [Permission.NETWORK_VIEW]
 * (GLOBAL → all, NETWORK-scoped → those networks, else empty). Mirrors [ServerVisibilityResolver]
 * but is keyed on network.view instead of server.view; both share [buildGrantIndex].
 */
class NetworkVisibilityResolver(private val userRepository: UserRepository, private val groupRepository: GroupRepository) {

    internal fun resolve(userId: Uuid): NetworkVisibility {
        val index = buildGrantIndex(userRepository, groupRepository, userId)
            ?: return NetworkVisibility(false, emptySet())
        val scopes = index.scopesGranting(Permission.NETWORK_VIEW)
        return NetworkVisibility(scopes.global, scopes.networkIds)
    }
}
