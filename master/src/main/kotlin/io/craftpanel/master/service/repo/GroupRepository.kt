package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

data class GroupRow(
    val id: Uuid,
    val name: String,
    val isSystem: Boolean,
    val permissions: List<String>,
    val createdAt: String,
)

interface GroupRepository {

    fun findById(id: Uuid): GroupRow?
    fun findByName(name: String): GroupRow?
    fun listAll(): List<GroupRow>
    fun getPermissions(groupId: Uuid): List<String>
    fun getPermissionsForGroups(groupIds: List<Uuid>): List<String>
}
