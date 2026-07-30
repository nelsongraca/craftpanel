package io.craftpanel.master.database.entity

import io.craftpanel.master.database.schema.Groups
import io.craftpanel.master.service.repo.GroupRow
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import kotlin.uuid.Uuid

class GroupEntity(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<GroupEntity>(Groups)

    var name by Groups.name
    var isSystem by Groups.isSystem
    var createdAt by Groups.createdAt

    fun toGroupRow(permissions: List<String> = emptyList()) = GroupRow(
        id = id.value,
        name = name,
        isSystem = isSystem,
        permissions = permissions,
        createdAt = createdAt.toUtcString()
    )
}
