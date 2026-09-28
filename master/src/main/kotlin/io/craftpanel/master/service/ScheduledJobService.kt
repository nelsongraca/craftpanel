package io.craftpanel.master.service

import io.craftpanel.master.domain.ScheduledJobType
import io.craftpanel.master.service.repo.ServerJobRepository
import io.craftpanel.master.service.repo.ServerJobRow
import io.craftpanel.master.service.repo.ServerRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class ScheduledJobResponse(
    val id: String,
    @SerialName("server_id") val serverId: String,
    val type: ScheduledJobType,
    @SerialName("cron_expression") val cronExpression: String,
    val payload: String?,
    val enabled: Boolean,
    @SerialName("last_fired_at") val lastFiredAt: String?
)

@Serializable
data class CreateScheduledJobRequest(
    val type: ScheduledJobType,
    @SerialName("cron_expression") val cronExpression: String,
    val payload: String? = null,
    // Nullable so a client that serializes nulls (the generated system-test client does) can omit
    // it; absence/null means enabled.
    val enabled: Boolean? = null
)

@Serializable
data class UpdateScheduledJobRequest(@SerialName("cron_expression") val cronExpression: String? = null, val payload: String? = null, val enabled: Boolean? = null)

/**
 * CRUD for user-defined per-server scheduled jobs. The scheduler (`ServerScheduler.tick`) is the
 * only executor; this service owns validation and persistence.
 *
 * `payload` is type-specific: required and command-shaped for `RCON_COMMAND`, ignored (forced null)
 * for every other type.
 */
class ScheduledJobService(private val serverRepository: ServerRepository, private val serverJobRepository: ServerJobRepository) {

    companion object {
        const val MAX_PAYLOAD_LENGTH = 512
    }

    fun list(serverId: Uuid): List<ScheduledJobResponse> {
        requireServer(serverId)
        return serverJobRepository.listByServer(serverId).map { it.toResponse() }
    }

    fun create(serverId: Uuid, req: CreateScheduledJobRequest): ScheduledJobResponse {
        requireServer(serverId)
        val cron = req.cronExpression.trim()
        if (!CronValidator.isValid(cron)) throw UnprocessableException("Invalid cron expression")
        val payload = normalizePayload(req.type, req.payload)
        val row = serverJobRepository.create(serverId, req.type.name, cron, payload, req.enabled ?: true)
        return row.toResponse()
    }

    fun update(serverId: Uuid, jobId: Uuid, req: UpdateScheduledJobRequest): ScheduledJobResponse {
        val existing = requireJob(serverId, jobId)
        val cron = req.cronExpression?.trim()
        if (cron != null && !CronValidator.isValid(cron)) throw UnprocessableException("Invalid cron expression")
        val payload = if (req.payload != null) normalizePayload(ScheduledJobType.fromDb(existing.type), req.payload) else null
        serverJobRepository.update(jobId, cron, payload, req.enabled)
        return requireJob(serverId, jobId).toResponse()
    }

    fun delete(serverId: Uuid, jobId: Uuid) {
        requireJob(serverId, jobId)
        serverJobRepository.delete(jobId)
    }

    /** Job types available for scheduling via the API, in stable declaration order. */
    fun availableTypes(): List<ScheduledJobType> = ScheduledJobType.entries.toList()

    private fun normalizePayload(type: ScheduledJobType, payload: String?): String? {
        if (!type.requiresPayload) return null
        val cleaned = payload?.replace('\n', ' ')?.replace('\r', ' ')?.trim()
        if (cleaned.isNullOrEmpty()) throw UnprocessableException("Command is required for ${type.name} jobs")
        if (cleaned.length > MAX_PAYLOAD_LENGTH) throw UnprocessableException("Command must be at most $MAX_PAYLOAD_LENGTH characters")
        return cleaned
    }

    private fun requireServer(serverId: Uuid) {
        if (serverRepository.findById(serverId) == null) throw NotFoundException("Server not found")
    }

    private fun requireJob(serverId: Uuid, jobId: Uuid): ServerJobRow {
        val row = serverJobRepository.findById(jobId) ?: throw NotFoundException("Job not found")
        if (row.serverId != serverId) throw NotFoundException("Job not found")
        return row
    }
}

private fun ServerJobRow.toResponse() = ScheduledJobResponse(
    id = id.toString(),
    serverId = serverId.toString(),
    type = ScheduledJobType.fromDb(type),
    cronExpression = cronExpression,
    payload = payload,
    enabled = enabled,
    lastFiredAt = lastFiredAt
)
