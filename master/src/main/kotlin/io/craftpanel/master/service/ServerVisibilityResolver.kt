package io.craftpanel.master.service

import io.craftpanel.master.auth.Permission
import io.craftpanel.master.service.repo.GroupRepository
import io.craftpanel.master.service.repo.UserRepository
import kotlin.uuid.Uuid

internal data class ServerVisibility(val isGlobal: Boolean, val networkIds: Set<Uuid>, val serverIds: Set<Uuid>)

class ServerVisibilityResolver(private val userRepository: UserRepository, private val groupRepository: GroupRepository) {

    internal fun resolve(userId: Uuid): ServerVisibility {
        val index = buildGrantIndex(userRepository, groupRepository, userId)
            ?: return ServerVisibility(false, emptySet(), emptySet())
        val scopes = index.scopesGranting(Permission.SERVER_VIEW)
        return ServerVisibility(scopes.global, scopes.networkIds, scopes.serverIds)
    }
}
