package io.craftpanel.master.service.repo.impl

import io.craftpanel.master.database.schema.*
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
}

private fun org.jetbrains.exposed.v1.core.ResultRow.toGroupRow(permissions: List<String>) = GroupRow(
    id = this[Groups.id].value,
    name = this[Groups.name],
    isSystem = this[Groups.isSystem],
    permissions = permissions,
    createdAt = this[Groups.createdAt].toUtcString()
)