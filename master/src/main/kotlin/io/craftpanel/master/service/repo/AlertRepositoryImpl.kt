package io.craftpanel.master.service.repo

import io.craftpanel.master.database.schema.AlertEvents
import io.craftpanel.master.database.schema.AlertThresholds
import io.craftpanel.master.util.toUtcString
import kotlinx.datetime.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class AlertRepositoryImpl : AlertRepository {

    override fun listThresholds(scopeType: String?, scopeId: Uuid?): List<AlertThresholdRow> = transaction {
        AlertThresholds.selectAll()
            .apply {
                val scopeTypeOp = if (scopeType != null) AlertThresholds.scopeType eq scopeType else null
                val scopeIdOp = if (scopeId != null) AlertThresholds.scopeId eq scopeId else null
                val condition = listOfNotNull(scopeTypeOp, scopeIdOp).reduceOrNull { a, b -> a and b }
                if (condition != null) where { condition }
            }
            .map { it.toThresholdRow() }
    }

    override fun createThreshold(scopeType: String, scopeId: Uuid, metric: String, thresholdValue: Double?, thresholdState: String?): AlertThresholdRow = transaction {
        val id = AlertThresholds.insert {
            it[AlertThresholds.scopeType] = scopeType
            it[AlertThresholds.scopeId] = scopeId
            it[AlertThresholds.metric] = metric
            it[AlertThresholds.thresholdValue] = thresholdValue
            it[AlertThresholds.thresholdState] = thresholdState
        }[AlertThresholds.id]
        AlertThresholds.selectAll()
            .where { AlertThresholds.id eq id }
            .first()
            .toThresholdRow()
    }

    override fun findThresholdById(id: Uuid): AlertThresholdRow? = transaction {
        AlertThresholds.selectAll()
            .where { AlertThresholds.id eq id }
            .firstOrNull()
            ?.toThresholdRow()
    }

    override fun deleteThreshold(id: Uuid) {
        transaction {
            AlertEvents.deleteWhere { AlertEvents.thresholdId eq id }
            AlertThresholds.deleteWhere { AlertThresholds.id eq id }
        }
    }

    override fun listEvents(thresholdIds: List<Uuid>?, activeOnly: Boolean): List<AlertEventRow> = transaction {
        AlertEvents.selectAll()
            .apply {
                val idsOp = if (thresholdIds != null) AlertEvents.thresholdId inList thresholdIds else null
                val activeOp = if (activeOnly) AlertEvents.resolvedAt.isNull() else null
                val condition = listOfNotNull(idsOp, activeOp).reduceOrNull { a, b -> a and b }
                if (condition != null) where { condition }
            }
            .orderBy(AlertEvents.firedAt, SortOrder.DESC)
            .map {
                AlertEventRow(
                    id = it[AlertEvents.id],
                    thresholdId = it[AlertEvents.thresholdId],
                    firedAt = it[AlertEvents.firedAt].toString(),
                    resolvedAt = it[AlertEvents.resolvedAt]?.toString(),
                    message = it[AlertEvents.message]
                )
            }
    }

    override fun findOpenEvent(thresholdId: Uuid): AlertEventRow? = transaction {
        AlertEvents.selectAll()
            .where { (AlertEvents.thresholdId eq thresholdId) and (AlertEvents.resolvedAt.isNull()) }
            .firstOrNull()
            ?.let {
                AlertEventRow(
                    id = it[AlertEvents.id],
                    thresholdId = it[AlertEvents.thresholdId],
                    firedAt = it[AlertEvents.firedAt].toString(),
                    resolvedAt = null,
                    message = it[AlertEvents.message]
                )
            }
    }

    override fun createEvent(thresholdId: Uuid, message: String): AlertEventRow = transaction {
        val id = AlertEvents.insert {
            it[AlertEvents.thresholdId] = thresholdId
            it[AlertEvents.message] = message
        }[AlertEvents.id]
        AlertEvents.selectAll()
            .where { AlertEvents.id eq id }
            .first()
            .let {
                AlertEventRow(
                    id = it[AlertEvents.id],
                    thresholdId = it[AlertEvents.thresholdId],
                    firedAt = it[AlertEvents.firedAt].toString(),
                    resolvedAt = null,
                    message = it[AlertEvents.message]
                )
            }
    }

    override fun resolveEventsForThreshold(thresholdId: Uuid, resolvedAt: Instant) {
        transaction {
            AlertEvents.update({ (AlertEvents.thresholdId eq thresholdId) and (AlertEvents.resolvedAt.isNull()) }) {
                it[AlertEvents.resolvedAt] = resolvedAt.toLocalDateTime(TimeZone.UTC)
            }
        }
    }

    override fun deleteEventsForThreshold(thresholdId: Uuid) {
        transaction { AlertEvents.deleteWhere { AlertEvents.thresholdId eq thresholdId } }
    }
}

private fun ResultRow.toThresholdRow() = AlertThresholdRow(
    id = this[AlertThresholds.id],
    scopeType = this[AlertThresholds.scopeType],
    scopeId = this[AlertThresholds.scopeId],
    metric = this[AlertThresholds.metric],
    thresholdValue = this[AlertThresholds.thresholdValue],
    thresholdState = this[AlertThresholds.thresholdState],
    createdAt = this[AlertThresholds.createdAt].toUtcString()
)
