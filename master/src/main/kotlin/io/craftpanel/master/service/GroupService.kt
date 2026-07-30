package io.craftpanel.master.service

import io.craftpanel.master.database.entity.GroupEntity
import io.craftpanel.master.database.schema.GroupPermissions
import io.craftpanel.master.database.schema.Groups
import io.craftpanel.master.database.schema.UserGroupAssignments
import io.craftpanel.master.auth.Permission
import io.craftpanel.master.service.repo.GroupRepository
import io.craftpanel.master.service.repo.GroupRow
import io.craftpanel.master.util.toUtcString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
        return transaction {
            val e = GroupEntity.new { this.name = req.name }
            val row = Groups.selectAll().where { Groups.id eq e.id }.first()
            GroupRow(
                id = row[Groups.id].value,
                name = row[Groups.name],
                isSystem = row[Groups.isSystem],
                permissions = emptyList(),
                createdAt = row[Groups.createdAt].toUtcString()
            )
        }.toResponse()
    }

    fun getGroup(targetId: Uuid): GroupResponse =
        groupRepository.findById(targetId)
            ?.toResponse() ?: throw NotFoundException("Group not found")

    fun updateGroup(targetId: Uuid, req: PatchGroupRequest): GroupResponse {
        val existing = groupRepository.findById(targetId) ?: throw NotFoundException("Group not found")
        if (existing.isSystem) throw ConflictException("Cannot modify a system group")
        transaction { GroupEntity.findById(targetId)?.let { it.name = req.name } }
        return groupRepository.findById(targetId)!!
            .toResponse()
    }

    fun deleteGroup(targetId: Uuid) {
        val existing = groupRepository.findById(targetId) ?: throw NotFoundException("Group not found")
        if (existing.isSystem) throw ConflictException("Cannot delete a system group")
        transaction {
            UserGroupAssignments.deleteWhere { UserGroupAssignments.groupId eq targetId }
            GroupPermissions.deleteWhere { GroupPermissions.groupId eq targetId }
            GroupEntity.findById(targetId)?.delete()
        }
    }

    fun setGroupPermissions(targetId: Uuid, req: PutGroupPermissionsRequest): GroupResponse {
        val existing = groupRepository.findById(targetId) ?: throw NotFoundException("Group not found")
        if (existing.isSystem) throw ConflictException("Cannot modify a system group")
        val invalid = req.permissions.filter { it !in VALID_PERMISSIONS }
        if (invalid.isNotEmpty()) throw BadRequestException("Invalid permission nodes: ${invalid.joinToString()}")
        transaction {
            GroupPermissions.deleteWhere { GroupPermissions.groupId eq targetId }
            req.permissions.distinct().forEach { perm ->
                GroupPermissions.insert {
                    it[GroupPermissions.groupId] = EntityID(targetId, Groups)
                    it[GroupPermissions.permission] = perm
                }
            }
        }
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
