package io.craftpanel.master.service.repo

import io.craftpanel.master.database.schema.GroupPermissions
import io.craftpanel.master.database.schema.Groups
import io.craftpanel.master.database.schema.UserGroupAssignments
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

class GroupRepositoryImpl : GroupRepository {

    override fun findById(id: Uuid): GroupRow? = transaction {
        val row = Groups.selectAll()
            .where { Groups.id eq id }
            .firstOrNull() ?: return@transaction null
        val perms = GroupPermissions.selectAll()
            .where { GroupPermissions.groupId eq id }
            .map { it[GroupPermissions.permission] }
        row.toGroupRow(perms)
    }

    override fun findByName(name: String): GroupRow? = transaction {
        val row = Groups.selectAll()
            .where { Groups.name eq name }
            .firstOrNull() ?: return@transaction null
        val perms = GroupPermissions.selectAll()
            .where { GroupPermissions.groupId eq row[Groups.id] }
            .map { it[GroupPermissions.permission] }
        row.toGroupRow(perms)
    }

    override fun listAll(): List<GroupRow> = transaction {
        val allPerms = GroupPermissions.selectAll()
            .groupBy { it[GroupPermissions.groupId] }
            .mapValues { (_, rows) -> rows.map { it[GroupPermissions.permission] } }
        Groups.selectAll()
            .map { row ->
                val groupId = row[Groups.id]
                row.toGroupRow(allPerms[groupId] ?: emptyList())
            }
    }

    override fun create(name: String, isSystem: Boolean): GroupRow = transaction {
        val id = Groups.insert {
            it[Groups.name] = name
            it[Groups.isSystem] = isSystem
        }[Groups.id]
        Groups.selectAll()
            .where { Groups.id eq id }
            .first()
            .toGroupRow(emptyList())
    }

    override fun update(id: Uuid, name: String) {
        transaction {
            Groups.update({ Groups.id eq id }) { it[Groups.name] = name }
        }
    }

    override fun delete(id: Uuid) {
        transaction {
            UserGroupAssignments.deleteWhere { UserGroupAssignments.groupId eq id }
            GroupPermissions.deleteWhere { GroupPermissions.groupId eq id }
            Groups.deleteWhere { Groups.id eq id }
        }
    }

    override fun getPermissions(groupId: Uuid): List<String> = transaction {
        GroupPermissions.selectAll()
            .where { GroupPermissions.groupId eq groupId }
            .map { it[GroupPermissions.permission] }
    }

    override fun getPermissionsForGroups(groupIds: List<Uuid>): List<String> = transaction {
        GroupPermissions.selectAll()
            .where { GroupPermissions.groupId inList groupIds }
            .map { it[GroupPermissions.permission] }
    }

    override fun setPermissions(groupId: Uuid, permissions: List<String>) {
        transaction {
            GroupPermissions.deleteWhere { GroupPermissions.groupId eq groupId }
            permissions.distinct()
                .forEach { perm ->
                    GroupPermissions.insert {
                        it[GroupPermissions.groupId] = groupId
                        it[GroupPermissions.permission] = perm
                    }
                }
        }
    }

    override fun deletePermissionsForGroup(groupId: Uuid) {
        transaction {
            GroupPermissions.deleteWhere { GroupPermissions.groupId eq groupId }
        }
    }
}

private fun org.jetbrains.exposed.v1.core.ResultRow.toGroupRow(permissions: List<String>) = GroupRow(
    id = this[Groups.id],
    name = this[Groups.name],
    isSystem = this[Groups.isSystem],
    permissions = permissions,
    createdAt = this[Groups.createdAt].toUtcString(),
)
