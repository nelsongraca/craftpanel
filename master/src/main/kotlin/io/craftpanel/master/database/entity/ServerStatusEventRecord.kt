package io.craftpanel.master.database.entity

import io.craftpanel.master.database.schema.ServerStatusEvents
import io.craftpanel.master.service.repo.ServerStatusEventRow
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import kotlin.uuid.Uuid

class ServerStatusEventRecord(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<ServerStatusEventRecord>(ServerStatusEvents)

    var serverId by ServerStatusEvents.serverId
    var status by ServerStatusEvents.status
    var recordedAt by ServerStatusEvents.recordedAt

    fun toRow() = ServerStatusEventRow(
        id = id.value,
        serverId = serverId.value,
        status = status,
        recordedAt = recordedAt.toUtcString()
    )
}
