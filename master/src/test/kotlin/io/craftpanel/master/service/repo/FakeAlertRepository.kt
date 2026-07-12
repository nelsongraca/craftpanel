package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

class FakeAlertRepository : AlertRepository {

    private val thresholds = mutableMapOf<Uuid, MutableThreshold>()
    private val events = mutableListOf<MutableEvent>()

    data class MutableThreshold(
        val id: Uuid,
        val scopeType: String,
        val scopeId: Uuid,
        val metric: String,
        var thresholdValue: Double?,
        var thresholdState: String?,
        val createdAt: String = "2025-01-01T00:00:00Z",
    )

    data class MutableEvent(
        val id: Uuid,
        val thresholdId: Uuid,
        val firedAt: String,
        var resolvedAt: String?,
        val message: String,
    )

    override fun listThresholds(scopeType: String?, scopeId: Uuid?): List<AlertThresholdRow> =
        thresholds.values.filter { t -> (scopeType == null || t.scopeType == scopeType) && (scopeId == null || t.scopeId == scopeId) }
            .map { it.toRow() }

    override fun createThreshold(scopeType: String, scopeId: Uuid, metric: String, thresholdValue: Double?, thresholdState: String?): AlertThresholdRow {
        val id = Uuid.random()
        val t = MutableThreshold(id, scopeType, scopeId, metric, thresholdValue, thresholdState)
        thresholds[id] = t
        return t.toRow()
    }

    override fun findThresholdById(id: Uuid): AlertThresholdRow? = thresholds[id]?.toRow()
    override fun deleteThreshold(id: Uuid) {
        thresholds.remove(id); events.removeAll { it.thresholdId == id }
    }

    override fun listEvents(thresholdIds: List<Uuid>?, activeOnly: Boolean): List<AlertEventRow> =
        events.filter { e -> (thresholdIds == null || e.thresholdId in thresholdIds) && (!activeOnly || e.resolvedAt == null) }
            .map { it.toRow() }

    override fun findOpenEvent(thresholdId: Uuid): AlertEventRow? = events.firstOrNull { it.thresholdId == thresholdId && it.resolvedAt == null }
        ?.toRow()

    override fun createEvent(thresholdId: Uuid, message: String): AlertEventRow {
        val id = Uuid.random()
        val e = MutableEvent(id, thresholdId, "2025-01-01T00:00:00Z", null, message)
        events.add(e)
        return e.toRow()
    }

    override fun resolveEventsForThreshold(thresholdId: Uuid, resolvedAt: kotlin.time.Instant) {
        events.filter { it.thresholdId == thresholdId && it.resolvedAt == null }
            .forEach { it.resolvedAt = resolvedAt.toString() }
    }

    override fun deleteEventsForThreshold(thresholdId: Uuid) {
        events.removeAll { it.thresholdId == thresholdId }
    }

    private fun MutableThreshold.toRow() = AlertThresholdRow(id, scopeType, scopeId, metric, thresholdValue, thresholdState, createdAt)
    private fun MutableEvent.toRow() = AlertEventRow(id, thresholdId, firedAt, resolvedAt, message)
}
