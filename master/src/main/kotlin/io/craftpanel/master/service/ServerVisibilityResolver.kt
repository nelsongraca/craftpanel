package io.craftpanel.master.service

import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.auth.ScopeType
import io.craftpanel.master.service.repo.GroupRepository
import io.craftpanel.master.service.repo.UserRepository
import kotlin.uuid.Uuid

class ServerVisibilityResolver(
    private val userRepository: UserRepository,
    private val groupRepository: GroupRepository,
) {

    internal fun resolve(userId: Uuid): ServerVisibility {
        if (!userRepository.isActive(userId)) return ServerVisibility(false, emptySet(), emptySet())
        val assignments = userRepository.listAssignments(userId)
        val groupIds = assignments.map { it.groupId }
            .toSet()
        if (groupIds.isEmpty()) return ServerVisibility(false, emptySet(), emptySet())
        val viewGroups = groupIds.filter { gid ->
            groupRepository.getPermissions(gid)
                .any { PermissionResolver.grants(it, Permission.SERVER_VIEW) }
        }
            .toSet()
        if (viewGroups.isEmpty()) return ServerVisibility(false, emptySet(), emptySet())
        var isGlobal = false
        val networkIds = mutableSetOf<Uuid>()
        val serverIds = mutableSetOf<Uuid>()
        for (a in assignments.filter { it.groupId in viewGroups }) {
            when (a.scopeType) {
                ScopeType.GLOBAL.name  -> isGlobal = true
                ScopeType.NETWORK.name -> a.scopeId?.let { networkIds += it }
                ScopeType.SERVER.name  -> a.scopeId?.let { serverIds += it }
            }
        }
        return ServerVisibility(isGlobal, networkIds, serverIds)
    }
}
