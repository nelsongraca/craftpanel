package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

data class AlertThresholdRow(
    val id: Uuid,
    val scopeType: String,
    val scopeId: Uuid,
    val metric: String,
    val thresholdValue: Double?,
    val thresholdState: String?,
    val createdAt: String,
)

data class AlertEventRow(
    val id: Uuid,
    val thresholdId: Uuid,
    val firedAt: String,
    val resolvedAt: String?,
    val message: String,
)

interface AlertRepository {

    fun listThresholds(scopeType: String?, scopeId: Uuid?): List<AlertThresholdRow>
    fun createThreshold(scopeType: String, scopeId: Uuid, metric: String, thresholdValue: Double?, thresholdState: String?): AlertThresholdRow
    fun findThresholdById(id: Uuid): AlertThresholdRow?
    fun deleteThreshold(id: Uuid)

    fun listEvents(thresholdIds: List<Uuid>?, activeOnly: Boolean): List<AlertEventRow>
    fun findOpenEvent(thresholdId: Uuid): AlertEventRow?
    fun createEvent(thresholdId: Uuid, message: String): AlertEventRow
    fun resolveEventsForThreshold(thresholdId: Uuid, resolvedAt: kotlin.time.Instant)
    fun deleteEventsForThreshold(thresholdId: Uuid)
}
