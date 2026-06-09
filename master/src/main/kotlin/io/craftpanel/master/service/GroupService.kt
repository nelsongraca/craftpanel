package io.craftpanel.master.service

import io.craftpanel.master.auth.Permission
import io.craftpanel.master.database.schema.GroupPermissions
import io.craftpanel.master.database.schema.Groups
import io.craftpanel.master.database.schema.UserGroupAssignments
import io.craftpanel.master.util.toKotlinUuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.*
import io.craftpanel.master.util.toUtcString

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

class GroupService {

    fun listGroups(): List<GroupResponse> = transaction { fetchAllGroups() }

    fun createGroup(req: CreateGroupRequest): GroupResponse {
        val exists = transaction {
            Groups.selectAll()
                .where { Groups.name eq req.name }
                .firstOrNull() != null
        }
        if (exists) throw ConflictException("Group name already taken")
        val createdId = transaction { Groups.insert { it[Groups.name] = req.name }[Groups.id] }
        return transaction { fetchGroup(UUID.fromString(createdId.toString()))!! }
    }

    fun getGroup(targetId: UUID): GroupResponse =
        transaction { fetchGroup(targetId) } ?: throw NotFoundException("Group not found")

    fun updateGroup(targetId: UUID, req: PatchGroupRequest): GroupResponse {
        val existing = transaction {
            Groups.selectAll()
                .where { Groups.id eq targetId.toKotlinUuid() }
                .firstOrNull()
        }
            ?: throw NotFoundException("Group not found")
        if (existing[Groups.isSystem]) throw ConflictException("Cannot modify a system group")
        transaction { Groups.update({ Groups.id eq targetId.toKotlinUuid() }) { it[Groups.name] = req.name } }
        return transaction { fetchGroup(targetId)!! }
    }

    fun deleteGroup(targetId: UUID) {
        val existing = transaction {
            Groups.selectAll()
                .where { Groups.id eq targetId.toKotlinUuid() }
                .firstOrNull()
        }
            ?: throw NotFoundException("Group not found")
        if (existing[Groups.isSystem]) throw ConflictException("Cannot delete a system group")
        transaction {
            UserGroupAssignments.deleteWhere { UserGroupAssignments.groupId eq targetId.toKotlinUuid() }
            GroupPermissions.deleteWhere { GroupPermissions.groupId eq targetId.toKotlinUuid() }
            Groups.deleteWhere { Groups.id eq targetId.toKotlinUuid() }
        }
    }

    fun setGroupPermissions(targetId: UUID, req: PutGroupPermissionsRequest): GroupResponse {
        val existing = transaction {
            Groups.selectAll()
                .where { Groups.id eq targetId.toKotlinUuid() }
                .firstOrNull()
        }
            ?: throw NotFoundException("Group not found")
        if (existing[Groups.isSystem]) throw ConflictException("Cannot modify a system group")
        val invalid = req.permissions.filter { it !in VALID_PERMISSIONS }
        if (invalid.isNotEmpty()) throw BadRequestException("Invalid permission nodes: ${invalid.joinToString()}")
        transaction {
            GroupPermissions.deleteWhere { GroupPermissions.groupId eq targetId.toKotlinUuid() }
            req.permissions.distinct()
                .forEach { perm ->
                    GroupPermissions.insert {
                        it[GroupPermissions.groupId] = targetId.toKotlinUuid()
                        it[GroupPermissions.permission] = perm
                    }
                }
        }
        return transaction { fetchGroup(targetId)!! }
    }
}

private fun fetchGroup(id: UUID): GroupResponse? {
    val row = Groups.selectAll()
        .where { Groups.id eq id.toKotlinUuid() }
        .firstOrNull() ?: return null
    val perms = GroupPermissions.selectAll()
        .where { GroupPermissions.groupId eq id.toKotlinUuid() }
        .map { it[GroupPermissions.permission] }
    return GroupResponse(
        id = row[Groups.id].toString(),
        name = row[Groups.name],
        isSystem = row[Groups.isSystem],
        permissions = perms,
        createdAt = row[Groups.createdAt].toUtcString(),
    )
}

private fun fetchAllGroups(): List<GroupResponse> {
    val allPerms = GroupPermissions.selectAll()
        .groupBy { it[GroupPermissions.groupId] }
        .mapValues { (_, rows) -> rows.map { it[GroupPermissions.permission] } }
    return Groups.selectAll()
        .map { row ->
            val groupId = row[Groups.id]
            GroupResponse(
                id = groupId.toString(),
                name = row[Groups.name],
                isSystem = row[Groups.isSystem],
                permissions = allPerms[groupId] ?: emptyList(),
                createdAt = row[Groups.createdAt].toUtcString(),
            )
        }
}
