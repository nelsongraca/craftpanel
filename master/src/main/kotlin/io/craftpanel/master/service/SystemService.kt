package io.craftpanel.master.service

import io.craftpanel.master.service.repo.SettingsRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid
import kotlin.time.Clock

@Serializable
data class SettingsMap(
    @SerialName("metric_retention_days") val metricRetentionDays: Int,
    @SerialName("default_backup_max_count") val defaultBackupMaxCount: Int,
    @SerialName("default_port_range_start") val defaultPortRangeStart: Int,
    @SerialName("default_port_range_end") val defaultPortRangeEnd: Int,
    @SerialName("restart_max_attempts") val restartMaxAttempts: Int,
    @SerialName("restart_window_seconds") val restartWindowSeconds: Long,
    @SerialName("rate_limit_login_per_minute") val rateLimitLoginPerMinute: Int,
    @SerialName("rate_limit_refresh_per_minute") val rateLimitRefreshPerMinute: Int,
    @SerialName("image_minecraft") val imageMinecraft: String,
    @SerialName("image_proxy") val imageProxy: String,
)

@Serializable
data class SystemSettingsResponse(
    val settings: SettingsMap,
    @SerialName("updated_at") val updatedAt: String?,
    @SerialName("updated_by") val updatedBy: String?,
)

@Serializable
data class PatchSettingsRequest(
    @SerialName("metric_retention_days") val metricRetentionDays: Int? = null,
    @SerialName("default_backup_max_count") val defaultBackupMaxCount: Int? = null,
    @SerialName("default_port_range_start") val defaultPortRangeStart: Int? = null,
    @SerialName("default_port_range_end") val defaultPortRangeEnd: Int? = null,
    @SerialName("restart_max_attempts") val restartMaxAttempts: Int? = null,
    @SerialName("restart_window_seconds") val restartWindowSeconds: Long? = null,
    @SerialName("rate_limit_login_per_minute") val rateLimitLoginPerMinute: Int? = null,
    @SerialName("rate_limit_refresh_per_minute") val rateLimitRefreshPerMinute: Int? = null,
    @SerialName("image_minecraft") val imageMinecraft: String? = null,
    @SerialName("image_proxy") val imageProxy: String? = null,
)

class SystemService(private val settingsRepository: SettingsRepository) {

    fun getSettings(): SystemSettingsResponse = loadSettings()

    fun updateSettings(updatedBy: Uuid, req: PatchSettingsRequest): SystemSettingsResponse {
        val portStart = req.defaultPortRangeStart
        val portEnd = req.defaultPortRangeEnd
        if (portStart != null && portEnd != null && portStart >= portEnd)
            throw UnprocessableException("default_port_range_start must be less than default_port_range_end")
        if (req.metricRetentionDays != null && req.metricRetentionDays < 1)
            throw UnprocessableException("metric_retention_days must be at least 1")
        if (req.defaultBackupMaxCount != null && req.defaultBackupMaxCount < 1)
            throw UnprocessableException("default_backup_max_count must be at least 1")
        if (req.restartMaxAttempts != null && req.restartMaxAttempts < 0)
            throw UnprocessableException("restart_max_attempts must be at least 0")
        if (req.restartWindowSeconds != null && req.restartWindowSeconds < 1)
            throw UnprocessableException("restart_window_seconds must be at least 1")
        if (req.rateLimitLoginPerMinute != null && req.rateLimitLoginPerMinute < 1)
            throw UnprocessableException("rate_limit_login_per_minute must be at least 1")
        if (req.rateLimitRefreshPerMinute != null && req.rateLimitRefreshPerMinute < 1)
            throw UnprocessableException("rate_limit_refresh_per_minute must be at least 1")
        if (req.imageMinecraft != null && req.imageMinecraft.isBlank())
            throw UnprocessableException("image_minecraft must not be blank")
        if (req.imageProxy != null && req.imageProxy.isBlank())
            throw UnprocessableException("image_proxy must not be blank")

        val now = Clock.System.now()
        val updates = buildMap {
            if (req.metricRetentionDays != null) put("metric_retention_days", req.metricRetentionDays.toString())
            if (req.defaultBackupMaxCount != null) put("default_backup_max_count", req.defaultBackupMaxCount.toString())
            if (req.defaultPortRangeStart != null) put("default_port_range_start", req.defaultPortRangeStart.toString())
            if (req.defaultPortRangeEnd != null) put("default_port_range_end", req.defaultPortRangeEnd.toString())
            if (req.restartMaxAttempts != null) put("restart_max_attempts", req.restartMaxAttempts.toString())
            if (req.restartWindowSeconds != null) put("restart_window_seconds", req.restartWindowSeconds.toString())
            if (req.rateLimitLoginPerMinute != null) put("rate_limit_login_per_minute", req.rateLimitLoginPerMinute.toString())
            if (req.rateLimitRefreshPerMinute != null) put("rate_limit_refresh_per_minute", req.rateLimitRefreshPerMinute.toString())
            if (req.imageMinecraft != null) put("image_minecraft", req.imageMinecraft)
            if (req.imageProxy != null) put("image_proxy", req.imageProxy)
        }
        updates.forEach { (k, v) -> settingsRepository.upsert(k, v, now, updatedBy) }

        val stored = loadSettings()
        val resolvedStart = req.defaultPortRangeStart ?: stored.settings.defaultPortRangeStart
        val resolvedEnd = req.defaultPortRangeEnd ?: stored.settings.defaultPortRangeEnd
        if (resolvedStart >= resolvedEnd)
            throw UnprocessableException("default_port_range_start must be less than default_port_range_end")
        return stored
    }

    private fun loadSettings(): SystemSettingsResponse {
        val rows = settingsRepository.getAll()
        val map = rows.associate { it.key to it.value }
        val latest = rows.maxByOrNull { it.updatedAt }
        return SystemSettingsResponse(
            settings = SettingsMap(
                metricRetentionDays = map["metric_retention_days"]?.toIntOrNull() ?: 30,
                defaultBackupMaxCount = map["default_backup_max_count"]?.toIntOrNull() ?: 10,
                defaultPortRangeStart = map["default_port_range_start"]?.toIntOrNull() ?: 25570,
                defaultPortRangeEnd = map["default_port_range_end"]?.toIntOrNull() ?: 26070,
                restartMaxAttempts = map["restart_max_attempts"]?.toIntOrNull() ?: 5,
                restartWindowSeconds = map["restart_window_seconds"]?.toLongOrNull() ?: 600L,
                rateLimitLoginPerMinute = map["rate_limit_login_per_minute"]?.toIntOrNull() ?: 10,
                rateLimitRefreshPerMinute = map["rate_limit_refresh_per_minute"]?.toIntOrNull() ?: 30,
                imageMinecraft = map["image_minecraft"] ?: "itzg/minecraft-server",
                imageProxy = map["image_proxy"] ?: "itzg/mc-proxy",
            ),
            updatedAt = latest?.updatedAt,
            updatedBy = latest?.updatedBy?.toString(),
        )
    }
}
