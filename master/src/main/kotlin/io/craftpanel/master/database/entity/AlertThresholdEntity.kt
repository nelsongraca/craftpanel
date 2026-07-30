package io.craftpanel.master.database.entity

import io.craftpanel.master.database.schema.AlertThresholds
import io.craftpanel.master.service.repo.AlertThresholdRow
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import kotlin.uuid.Uuid

class AlertThresholdEntity(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<AlertThresholdEntity>(AlertThresholds)

    var scopeType by AlertThresholds.scopeType
    var scopeId by AlertThresholds.scopeId
    var metric by AlertThresholds.metric
    var thresholdValue by AlertThresholds.thresholdValue
    var thresholdState by AlertThresholds.thresholdState
    var createdAt by AlertThresholds.createdAt

    fun toThresholdRow() = AlertThresholdRow(
        id = id.value,
        scopeType = scopeType,
        scopeId = scopeId,
        metric = metric,
        thresholdValue = thresholdValue,
        thresholdState = thresholdState,
        createdAt = createdAt.toUtcString()
    )
}
