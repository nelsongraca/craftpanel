package io.craftpanel.master.database.entity

import io.craftpanel.master.database.schema.ServerMigrations
import io.craftpanel.master.service.repo.MigrationRow
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import kotlin.uuid.Uuid

class ServerMigrationEntity(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<ServerMigrationEntity>(ServerMigrations)

    var serverId by ServerMigrations.serverId
    var sourceNodeId by ServerMigrations.sourceNodeId
    var targetNodeId by ServerMigrations.targetNodeId
    var status by ServerMigrations.status
    var createdAt by ServerMigrations.createdAt
    var completedAt by ServerMigrations.completedAt

    fun toMigrationRow() = MigrationRow(
        id = id.value,
        serverId = serverId.value,
        sourceNodeId = sourceNodeId.value,
        targetNodeId = targetNodeId.value,
        status = status,
        createdAt = createdAt.toUtcString(),
        completedAt = completedAt?.toUtcString()
    )
}
