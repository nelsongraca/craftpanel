package io.craftpanel.master.database.entity

import io.craftpanel.master.database.schema.ServerJobs
import io.craftpanel.master.service.repo.ServerJobRow
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import kotlin.uuid.Uuid

class ServerJobEntity(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<ServerJobEntity>(ServerJobs)

    var serverId by ServerJobs.serverId
    var type by ServerJobs.type
    var cronExpression by ServerJobs.cronExpression
    var enabled by ServerJobs.enabled
    var lastFiredAt by ServerJobs.lastFiredAt

    fun toServerJobRow() = ServerJobRow(
        id = id.value,
        serverId = serverId.value,
        type = type,
        cronExpression = cronExpression,
        lastFiredAt = lastFiredAt?.toUtcString()
    )
}
