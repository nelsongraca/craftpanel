package io.craftpanel.master.service.repo

import kotlinx.datetime.Instant
import kotlin.uuid.Uuid

data class SettingsEntry(val key: String, val value: String, val updatedAt: String, val updatedBy: Uuid?)

data class ServerJobRow(
    val id: Uuid,
    val serverId: Uuid,
    val type: String,
    val cronExpression: String,
    val enabled: Boolean,
    val lastFiredAt: String?,
)

interface SettingsRepository {

    fun getAll(): List<SettingsEntry>
    fun upsert(key: String, value: String, updatedAt: Instant?, updatedBy: Uuid?)
    fun findJobsByType(type: String): List<ServerJobRow>
    fun updateJobLastFired(jobId: Uuid, lastFiredAt: Instant)
    fun findEnabledJobs(): List<ServerJobRow>
}
