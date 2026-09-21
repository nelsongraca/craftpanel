package io.craftpanel.master.service

import io.craftpanel.master.database.schema.SystemSettings
import io.craftpanel.master.database.schema.Users
import io.craftpanel.master.service.repo.SettingsRepository
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.time.Clock
import kotlin.uuid.Uuid

@Serializable
data class SystemSettingsResponse(val settings: Settings, @SerialName("updated_at") val updatedAt: String?, @SerialName("updated_by") val updatedBy: String?)

@Serializable
data class PatchSettingsRequest(
    @SerialName("app_name") val appName: String? = null,
    @SerialName("app_logo") val appLogo: String? = null,
    @SerialName("metric_retention_days") val metricRetentionDays: Int? = null,
    @SerialName("default_backup_max_count") val defaultBackupMaxCount: Int? = null,
    @SerialName("default_port_range_start") val defaultPortRangeStart: Int? = null,
    @SerialName("default_port_range_end") val defaultPortRangeEnd: Int? = null,
    @SerialName("restart_max_attempts") val restartMaxAttempts: Int? = null,
    @SerialName("restart_window_seconds") val restartWindowSeconds: Long? = null,
    @SerialName("rate_limit_login_per_minute") val rateLimitLoginPerMinute: Int? = null,
    @SerialName("rate_limit_refresh_per_minute") val rateLimitRefreshPerMinute: Int? = null,
    @SerialName("rate_limit_totp_verify_per_minute") val rateLimitTotpVerifyPerMinute: Int? = null,
    @SerialName("image_minecraft") val imageMinecraft: String? = null,
    @SerialName("image_proxy") val imageProxy: String? = null,
    @SerialName("console_tail_lines") val consoleTailLines: Int? = null,
    @SerialName("dns_domain_suffix") val dnsDomainSuffix: String? = null,
    @SerialName("dns_zone_id") val dnsZoneId: String? = null
)

class SystemService(private val settingsRepository: SettingsRepository, private val settingsProvider: SettingsProvider) {

    fun getSettings(): SystemSettingsResponse = loadSettings()

    fun updateSettings(updatedBy: Uuid, req: PatchSettingsRequest): SystemSettingsResponse {
        val portStart = req.defaultPortRangeStart
        val portEnd = req.defaultPortRangeEnd
        if (portStart != null && portEnd != null && portStart >= portEnd) {
            throw UnprocessableException("default_port_range_start must be less than default_port_range_end")
        }
        if (req.metricRetentionDays != null && req.metricRetentionDays < 1) {
            throw UnprocessableException("metric_retention_days must be at least 1")
        }
        if (req.defaultBackupMaxCount != null && req.defaultBackupMaxCount < 1) {
            throw UnprocessableException("default_backup_max_count must be at least 1")
        }
        if (req.restartMaxAttempts != null && req.restartMaxAttempts < 0) {
            throw UnprocessableException("restart_max_attempts must be at least 0")
        }
        if (req.restartWindowSeconds != null && req.restartWindowSeconds < 1) {
            throw UnprocessableException("restart_window_seconds must be at least 1")
        }
        if (req.rateLimitLoginPerMinute != null && req.rateLimitLoginPerMinute < 1) {
            throw UnprocessableException("rate_limit_login_per_minute must be at least 1")
        }
        if (req.rateLimitRefreshPerMinute != null && req.rateLimitRefreshPerMinute < 1) {
            throw UnprocessableException("rate_limit_refresh_per_minute must be at least 1")
        }
        if (req.rateLimitTotpVerifyPerMinute != null && req.rateLimitTotpVerifyPerMinute < 1) {
            throw UnprocessableException("rate_limit_totp_verify_per_minute must be at least 1")
        }
        if (req.imageMinecraft != null && req.imageMinecraft.isBlank()) {
            throw UnprocessableException("image_minecraft must not be blank")
        }
        if (req.appName != null && req.appName.isBlank()) {
            throw UnprocessableException("app_name must not be blank")
        }
        if (req.imageProxy != null && req.imageProxy.isBlank()) {
            throw UnprocessableException("image_proxy must not be blank")
        }
        if (req.consoleTailLines != null && req.consoleTailLines !in 1..5000) {
            throw UnprocessableException("console_tail_lines must be between 1 and 5000")
        }

        val now = Clock.System.now()
        val updates = buildMap {
            if (req.appName != null) put("app_name", req.appName)
            if (req.appLogo != null) put("app_logo", req.appLogo)
            if (req.metricRetentionDays != null) put("metric_retention_days", req.metricRetentionDays.toString())
            if (req.defaultBackupMaxCount != null) put("default_backup_max_count", req.defaultBackupMaxCount.toString())
            if (req.defaultPortRangeStart != null) put("default_port_range_start", req.defaultPortRangeStart.toString())
            if (req.defaultPortRangeEnd != null) put("default_port_range_end", req.defaultPortRangeEnd.toString())
            if (req.restartMaxAttempts != null) put("restart_max_attempts", req.restartMaxAttempts.toString())
            if (req.restartWindowSeconds != null) put("restart_window_seconds", req.restartWindowSeconds.toString())
            if (req.rateLimitLoginPerMinute != null) put("rate_limit_login_per_minute", req.rateLimitLoginPerMinute.toString())
            if (req.rateLimitRefreshPerMinute != null) put("rate_limit_refresh_per_minute", req.rateLimitRefreshPerMinute.toString())
            if (req.rateLimitTotpVerifyPerMinute != null) put("rate_limit_totp_verify_per_minute", req.rateLimitTotpVerifyPerMinute.toString())
            if (req.imageMinecraft != null) put("image_minecraft", req.imageMinecraft)
            if (req.imageProxy != null) put("image_proxy", req.imageProxy)
            if (req.consoleTailLines != null) put("console_tail_lines", req.consoleTailLines.toString())
            if (req.dnsDomainSuffix != null) put("dns_domain_suffix", req.dnsDomainSuffix)
            if (req.dnsZoneId != null) put("dns_zone_id", req.dnsZoneId)
        }
        transaction {
            updates.forEach { (k, v) ->
                SystemSettings.upsert {
                    it[SystemSettings.key] = k
                    it[SystemSettings.value] = v
                    it[SystemSettings.updatedBy] = EntityID(updatedBy, Users)
                    it[SystemSettings.updatedAt] = now.toLocalDateTime(TimeZone.UTC)
                }
            }
        }

        val stored = loadSettings()
        settingsProvider.invalidate()
        val resolvedStart = req.defaultPortRangeStart ?: stored.settings.defaultPortRangeStart
        val resolvedEnd = req.defaultPortRangeEnd ?: stored.settings.defaultPortRangeEnd
        if (resolvedStart >= resolvedEnd) {
            throw UnprocessableException("default_port_range_start must be less than default_port_range_end")
        }
        return stored
    }

    private fun loadSettings(): SystemSettingsResponse {
        val rows = settingsRepository.getAll()
        val latest = rows.maxByOrNull { it.updatedAt }
        return SystemSettingsResponse(
            settings = Settings.from(rows),
            updatedAt = latest?.updatedAt,
            updatedBy = latest?.updatedBy?.toString()
        )
    }
}
