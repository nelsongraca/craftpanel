package io.craftpanel.master.service

import io.craftpanel.master.auth.Permission
import io.craftpanel.master.service.repo.GroupRepository
import io.craftpanel.master.service.repo.GroupRow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class GroupResponse(
    val id: String,
    val name: String,
    @SerialName("is_system") val isSystem: Boolean,
    val permissions: List<String>,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class CreateGroupRequest(val name: String)

@Serializable
data class PatchGroupRequest(val name: String)

@Serializable
data class PutGroupPermissionsRequest(val permissions: List<String>)

private val VALID_PERMISSIONS = Permission.entries.map { it.node }
    .toSet()

class GroupService(private val groupRepository: GroupRepository) {

    fun listGroups(): List<GroupResponse> =
        groupRepository.listAll()
            .map { it.toResponse() }

    fun createGroup(req: CreateGroupRequest): GroupResponse {
        if (groupRepository.findByName(req.name) != null)
            throw ConflictException("Group name already taken")
        return groupRepository.create(req.name)
            .toResponse()
    }

    fun getGroup(targetId: Uuid): GroupResponse =
        groupRepository.findById(targetId)
            ?.toResponse() ?: throw NotFoundException("Group not found")

    fun updateGroup(targetId: Uuid, req: PatchGroupRequest): GroupResponse {
        val existing = groupRepository.findById(targetId) ?: throw NotFoundException("Group not found")
        if (existing.isSystem) throw ConflictException("Cannot modify a system group")
        groupRepository.update(targetId, req.name)
        return groupRepository.findById(targetId)!!
            .toResponse()
    }

    fun deleteGroup(targetId: Uuid) {
        val existing = groupRepository.findById(targetId) ?: throw NotFoundException("Group not found")
        if (existing.isSystem) throw ConflictException("Cannot delete a system group")
        groupRepository.delete(targetId)
    }

    fun setGroupPermissions(targetId: Uuid, req: PutGroupPermissionsRequest): GroupResponse {
        val existing = groupRepository.findById(targetId) ?: throw NotFoundException("Group not found")
        if (existing.isSystem) throw ConflictException("Cannot modify a system group")
        val invalid = req.permissions.filter { it !in VALID_PERMISSIONS }
        if (invalid.isNotEmpty()) throw BadRequestException("Invalid permission nodes: ${invalid.joinToString()}")
        groupRepository.setPermissions(targetId, req.permissions.distinct())
        return groupRepository.findById(targetId)!!
            .toResponse()
    }
}

private fun GroupRow.toResponse() = GroupResponse(
    id = id.toString(),
    name = name,
    isSystem = isSystem,
    permissions = permissions,
    createdAt = createdAt,
)
