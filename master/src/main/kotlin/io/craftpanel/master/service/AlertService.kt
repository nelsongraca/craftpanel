package io.craftpanel.master.service

import io.craftpanel.master.auth.ScopeType
import io.craftpanel.master.database.schema.AlertEvents
import io.craftpanel.master.database.schema.AlertThresholds
import io.craftpanel.master.util.toKotlinUuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.*

@Serializable
data class AlertThresholdResponse(
    val id: String,
    @SerialName("scope_type") val scopeType: String,
    @SerialName("scope_id") val scopeId: String,
    val metric: String,
    @SerialName("threshold_value") val thresholdValue: Double? = null,
    @SerialName("threshold_state") val thresholdState: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class AlertEventResponse(
    val id: String,
    @SerialName("threshold_id") val thresholdId: String,
    val message: String,
    @SerialName("fired_at") val firedAt: String,
    @SerialName("resolved_at") val resolvedAt: String? = null,
)

@Serializable
data class CreateAlertThresholdRequest(
    @SerialName("scope_type") val scopeType: String,
    @SerialName("scope_id") val scopeId: String,
    val metric: String,
    @SerialName("threshold_value") val thresholdValue: Double? = null,
    @SerialName("threshold_state") val thresholdState: String? = null,
)

class AlertService {

    fun listThresholds(scopeType: String?, scopeId: kotlin.uuid.Uuid?): List<AlertThresholdResponse> =
        transaction {
            AlertThresholds.selectAll()
                .apply {
                    if (scopeType != null) where { AlertThresholds.scopeType eq scopeType }
                    if (scopeId != null) where { AlertThresholds.scopeId eq scopeId }
                }
                .map { row ->
                    AlertThresholdResponse(
                        id = row[AlertThresholds.id].toString(),
                        scopeType = row[AlertThresholds.scopeType],
                        scopeId = row[AlertThresholds.scopeId].toString(),
                        metric = row[AlertThresholds.metric],
                        thresholdValue = row[AlertThresholds.thresholdValue],
                        thresholdState = row[AlertThresholds.thresholdState],
                        createdAt = row[AlertThresholds.createdAt].toString(),
                    )
                }
        }

    fun createThreshold(req: CreateAlertThresholdRequest): AlertThresholdResponse {
        if ((req.thresholdValue == null) == (req.thresholdState == null))
            throw UnprocessableException("Exactly one of threshold_value or threshold_state must be provided")
        if (req.scopeType !in setOf(ScopeType.NODE.name, ScopeType.SERVER.name))
            throw UnprocessableException("scope_type must be NODE or SERVER")
        val scopeKotlinId = runCatching {
            UUID.fromString(req.scopeId)
                .toKotlinUuid()
        }.getOrNull()
            ?: throw UnprocessableException("Invalid scope_id")
        return transaction {
            val id = AlertThresholds.insert {
                it[AlertThresholds.scopeType] = req.scopeType
                it[AlertThresholds.scopeId] = scopeKotlinId
                it[AlertThresholds.metric] = req.metric
                it[AlertThresholds.thresholdValue] = req.thresholdValue
                it[AlertThresholds.thresholdState] = req.thresholdState
            }[AlertThresholds.id]
            AlertThresholds.selectAll()
                .where { AlertThresholds.id eq id }
                .first()
                .let { row ->
                    AlertThresholdResponse(
                        id = row[AlertThresholds.id].toString(),
                        scopeType = row[AlertThresholds.scopeType],
                        scopeId = row[AlertThresholds.scopeId].toString(),
                        metric = row[AlertThresholds.metric],
                        thresholdValue = row[AlertThresholds.thresholdValue],
                        thresholdState = row[AlertThresholds.thresholdState],
                        createdAt = row[AlertThresholds.createdAt].toString(),
                    )
                }
        }
    }

    fun deleteThreshold(id: kotlin.uuid.Uuid) {
        val deleted = transaction {
            AlertEvents.deleteWhere { AlertEvents.thresholdId eq id }
            AlertThresholds.deleteWhere { AlertThresholds.id eq id }
        }
        if (deleted == 0) throw NotFoundException("Threshold not found")
    }

    fun listEvents(scopeType: String?, scopeId: kotlin.uuid.Uuid?, activeOnly: Boolean): List<AlertEventResponse> =
        transaction {
            (AlertEvents innerJoin AlertThresholds)
                .selectAll()
                .apply {
                    if (scopeType != null) where { AlertThresholds.scopeType eq scopeType }
                    if (scopeId != null) where { AlertThresholds.scopeId eq scopeId }
                    if (activeOnly) where { AlertEvents.resolvedAt.isNull() }
                }
                .orderBy(AlertEvents.firedAt, SortOrder.DESC)
                .map { row ->
                    AlertEventResponse(
                        id = row[AlertEvents.id].toString(),
                        thresholdId = row[AlertEvents.thresholdId].toString(),
                        message = row[AlertEvents.message],
                        firedAt = row[AlertEvents.firedAt].toString(),
                        resolvedAt = row[AlertEvents.resolvedAt]?.toString(),
                    )
                }
        }
}
