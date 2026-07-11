package io.craftpanel.master.service

import io.craftpanel.master.auth.ScopeType
import io.craftpanel.master.service.repo.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class AlertThresholdResponse(
    val id: String,
    @SerialName("scope_type") val scopeType: ScopeType,
    @SerialName("scope_id") val scopeId: String,
    val metric: String,
    @SerialName("threshold_value") val thresholdValue: Double? = null,
    @SerialName("threshold_state") val thresholdState: String? = null,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class AlertEventResponse(
    val id: String,
    @SerialName("threshold_id") val thresholdId: String,
    val message: String,
    @SerialName("fired_at") val firedAt: String,
    @SerialName("resolved_at") val resolvedAt: String? = null
)

@Serializable
data class CreateAlertThresholdRequest(
    @SerialName("scope_type") val scopeType: ScopeType,
    @SerialName("scope_id") val scopeId: String,
    val metric: String,
    @SerialName("threshold_value") val thresholdValue: Double? = null,
    @SerialName("threshold_state") val thresholdState: String? = null
)

class AlertService(private val alertRepository: AlertRepository, private val nodeRepository: NodeRepository, private val serverRepository: ServerRepository) {

    fun listThresholds(scopeType: String?, scopeId: Uuid?): List<AlertThresholdResponse> = alertRepository.listThresholds(scopeType, scopeId)
        .map { it.toResponse() }

    fun createThreshold(req: CreateAlertThresholdRequest): AlertThresholdResponse {
        if ((req.thresholdValue == null) == (req.thresholdState == null)) {
            throw UnprocessableException("Exactly one of threshold_value or threshold_state must be provided")
        }
        if (req.scopeType != ScopeType.NODE && req.scopeType != ScopeType.SERVER) {
            throw UnprocessableException("scope_type must be NODE or SERVER")
        }
        val scopeKotlinId = runCatching {
            Uuid.parse(req.scopeId)
        }.getOrNull()
            ?: throw UnprocessableException("Invalid scope_id")
        val scopeExists = when (req.scopeType) {
            ScopeType.NODE -> nodeRepository.findById(scopeKotlinId) != null
            ScopeType.SERVER -> serverRepository.findById(scopeKotlinId) != null
        }
        if (!scopeExists) throw UnprocessableException("scope_id does not reference an existing ${req.scopeType.name.lowercase()}")
        return alertRepository.createThreshold(
            scopeType = req.scopeType.name,
            scopeId = scopeKotlinId,
            metric = req.metric,
            thresholdValue = req.thresholdValue,
            thresholdState = req.thresholdState
        )
            .toResponse()
    }

    fun deleteThreshold(id: Uuid) {
        if (alertRepository.findThresholdById(id) == null) throw NotFoundException("Threshold not found")
        alertRepository.deleteThreshold(id)
    }

    fun listEvents(scopeType: String?, scopeId: Uuid?, activeOnly: Boolean): List<AlertEventResponse> {
        val thresholdIds = if (scopeType != null || scopeId != null) {
            val thresholds = alertRepository.listThresholds(scopeType, scopeId)
            thresholds.map { it.id }
                .takeIf { it.isNotEmpty() } ?: return emptyList()
        } else {
            null
        }
        return alertRepository.listEvents(thresholdIds, activeOnly)
            .map { it.toResponse() }
    }
}

private fun AlertThresholdRow.toResponse() = AlertThresholdResponse(
    id = id.toString(),
    scopeType = ScopeType.valueOf(scopeType),
    scopeId = scopeId.toString(),
    metric = metric,
    thresholdValue = thresholdValue,
    thresholdState = thresholdState,
    createdAt = createdAt
)

private fun AlertEventRow.toResponse() = AlertEventResponse(
    id = id.toString(),
    thresholdId = thresholdId.toString(),
    message = message,
    firedAt = firedAt,
    resolvedAt = resolvedAt
)
