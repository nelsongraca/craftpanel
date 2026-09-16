package io.craftpanel.master.service

import io.craftpanel.master.auth.*
import io.craftpanel.master.service.repo.GroupRepository
import io.craftpanel.master.service.repo.UserRepository
import kotlin.uuid.Uuid

internal data class ServerVisibility(val isGlobal: Boolean, val networkIds: Set<Uuid>, val serverIds: Set<Uuid>)

class ServerVisibilityResolver(private val userRepository: UserRepository, private val groupRepository: GroupRepository) {

    internal fun resolve(userId: Uuid): ServerVisibility {
        if (!userRepository.isActive(userId)) return ServerVisibility(false, emptySet(), emptySet())
        val assignments = userRepository.listAssignments(userId)
        if (assignments.isEmpty()) return ServerVisibility(false, emptySet(), emptySet())

        val permissionsByGroup = assignments.map { it.groupId }
            .toSet()
            .associateWith { groupRepository.getPermissions(it).toSet() }
        val scopes = GrantIndex.from(
            assignments.map { AssignmentScope(it.groupId, it.scopeType, it.scopeId) },
            permissionsByGroup
        ).scopesGranting(Permission.SERVER_VIEW)

        return ServerVisibility(scopes.global, scopes.networkIds, scopes.serverIds)
    }
}
