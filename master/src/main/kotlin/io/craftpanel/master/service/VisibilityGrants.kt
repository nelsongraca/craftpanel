package io.craftpanel.master.service

import io.craftpanel.master.auth.AssignmentScope
import io.craftpanel.master.auth.GrantIndex
import io.craftpanel.master.service.repo.GroupRepository
import io.craftpanel.master.service.repo.UserRepository
import kotlin.uuid.Uuid

/**
 * Build a user's [GrantIndex] from their active group assignments and each group's permission nodes.
 * Returns null when the user is inactive or has no assignments — the shared prelude of both
 * visibility resolvers. Callers ask the index for the scopes granting their permission.
 */
internal fun buildGrantIndex(userRepository: UserRepository, groupRepository: GroupRepository, userId: Uuid): GrantIndex? {
    if (!userRepository.isActive(userId)) return null
    val assignments = userRepository.listAssignments(userId)
    if (assignments.isEmpty()) return null
    val permissionsByGroup = assignments.map { it.groupId }
        .toSet()
        .associateWith { groupRepository.getPermissions(it).toSet() }
    return GrantIndex.from(
        assignments.map { AssignmentScope(it.groupId, it.scopeType, it.scopeId) },
        permissionsByGroup
    )
}
