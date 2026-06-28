package io.craftpanel.master.service.repo

import kotlinx.datetime.Instant
import kotlin.uuid.Uuid

class FakeSettingsRepository : SettingsRepository {

    private val settings = mutableMapOf<String, String>()
    private var updatedByValue: Uuid? = null
    private val jobs = mutableMapOf<Uuid, MutableServerJob>()

    data class MutableServerJob(
        val id: Uuid,
        val serverId: Uuid,
        val type: String,
        val cronExpression: String,
        var enabled: Boolean = true,
        var lastFiredAt: String? = null,
    )

    override fun getAll(): List<SettingsEntry> = settings.map { (k, v) -> SettingsEntry(k, v, "2025-01-01T00:00:00Z", updatedByValue) }
    override fun upsert(key: String, value: String, updatedAt: Instant?, updatedBy: Uuid?) {
        settings[key] = value
        if (updatedBy != null) updatedByValue = updatedBy
    }

    override fun findJobsByType(type: String): List<ServerJobRow> = jobs.values.filter { it.type == type }
        .map { it.toRow() }

    override fun updateJobLastFired(jobId: Uuid, lastFiredAt: Instant) {
        jobs[jobId]?.lastFiredAt = lastFiredAt.toString()
    }

    override fun findEnabledJobs(): List<ServerJobRow> = jobs.values.filter { it.enabled }
        .map { it.toRow() }

    fun addJob(job: MutableServerJob) {
        jobs[job.id] = job
    }

    private fun MutableServerJob.toRow() = ServerJobRow(id, serverId, type, cronExpression, enabled, lastFiredAt)
}
