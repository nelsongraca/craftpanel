package io.craftpanel.master.service

import io.craftpanel.master.database.schema.SystemSettings
import io.craftpanel.master.util.toKotlinUuid
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.util.UUID

@Serializable
data class SettingsMap(
    @SerialName("metric_retention_days") val metricRetentionDays: Int,
    @SerialName("default_backup_max_count") val defaultBackupMaxCount: Int,
    @SerialName("default_port_range_start") val defaultPortRangeStart: Int,
    @SerialName("default_port_range_end") val defaultPortRangeEnd: Int,
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
)

class SystemService {

    fun getSettings(): SystemSettingsResponse = transaction { loadSettings() }

    fun updateSettings(updatedBy: UUID, req: PatchSettingsRequest): SystemSettingsResponse {
        val portStart = req.defaultPortRangeStart
        val portEnd = req.defaultPortRangeEnd
        if (portStart != null && portEnd != null && portStart >= portEnd)
            throw UnprocessableException("default_port_range_start must be less than default_port_range_end")
        if (req.metricRetentionDays != null && req.metricRetentionDays < 1)
            throw UnprocessableException("metric_retention_days must be at least 1")
        if (req.defaultBackupMaxCount != null && req.defaultBackupMaxCount < 1)
            throw UnprocessableException("default_backup_max_count must be at least 1")

        val updatedByKotlin = updatedBy.toKotlinUuid()
        transaction {
            val updates = buildMap<String, String> {
                if (req.metricRetentionDays != null) put("metric_retention_days", req.metricRetentionDays.toString())
                if (req.defaultBackupMaxCount != null) put("default_backup_max_count", req.defaultBackupMaxCount.toString())
                if (req.defaultPortRangeStart != null) put("default_port_range_start", req.defaultPortRangeStart.toString())
                if (req.defaultPortRangeEnd != null) put("default_port_range_end", req.defaultPortRangeEnd.toString())
            }
            updates.forEach { (k, v) ->
                SystemSettings.upsert {
                    it[SystemSettings.key] = k
                    it[SystemSettings.value] = v
                    it[SystemSettings.updatedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                    it[SystemSettings.updatedBy] = updatedByKotlin
                }
            }
        }

        val stored = transaction { loadSettings() }
        val resolvedStart = req.defaultPortRangeStart ?: stored.settings.defaultPortRangeStart
        val resolvedEnd = req.defaultPortRangeEnd ?: stored.settings.defaultPortRangeEnd
        if (resolvedStart >= resolvedEnd)
            throw UnprocessableException("default_port_range_start must be less than default_port_range_end")
        return stored
    }
}

private fun loadSettings(): SystemSettingsResponse {
    val rows = SystemSettings.selectAll().toList()
    val map = rows.associate { it[SystemSettings.key] to it[SystemSettings.value] }
    val latest = rows.maxByOrNull { it[SystemSettings.updatedAt] }
    return SystemSettingsResponse(
        settings = SettingsMap(
            metricRetentionDays = map["metric_retention_days"]?.toIntOrNull() ?: 30,
            defaultBackupMaxCount = map["default_backup_max_count"]?.toIntOrNull() ?: 10,
            defaultPortRangeStart = map["default_port_range_start"]?.toIntOrNull() ?: 25570,
            defaultPortRangeEnd = map["default_port_range_end"]?.toIntOrNull() ?: 26070,
        ),
        updatedAt = latest?.get(SystemSettings.updatedAt)?.toString(),
        updatedBy = latest?.get(SystemSettings.updatedBy)?.toString(),
    )
}
