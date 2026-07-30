package io.craftpanel.master.database.entity

import io.craftpanel.master.database.schema.AlertEvents
import io.craftpanel.master.service.repo.AlertEventRow
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import kotlin.uuid.Uuid

class AlertEventEntity(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<AlertEventEntity>(AlertEvents)

    var thresholdId by AlertEvents.thresholdId
    var firedAt by AlertEvents.firedAt
    var resolvedAt by AlertEvents.resolvedAt
    var message by AlertEvents.message

    fun toEventRow() = AlertEventRow(
        id = id.value,
        thresholdId = thresholdId.value,
        firedAt = firedAt.toUtcString(),
        resolvedAt = resolvedAt?.toUtcString(),
        message = message
    )
}
