package io.craftpanel.master.service

import io.craftpanel.master.service.repo.SettingsEntry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The typed snapshot of system settings: the one place that knows every settings key, its default,
 * and its blank-means-unset rule. Built from raw [SettingsEntry] rows via [from].
 */
@Serializable
data class Settings(
    @SerialName("app_name") val appName: String,
    @SerialName("app_logo") val appLogo: String?,
    @SerialName("metric_retention_days") val metricRetentionDays: Int,
    @SerialName("default_backup_max_count") val defaultBackupMaxCount: Int,
    @SerialName("default_port_range_start") val defaultPortRangeStart: Int,
    @SerialName("default_port_range_end") val defaultPortRangeEnd: Int,
    @SerialName("restart_max_attempts") val restartMaxAttempts: Int,
    @SerialName("restart_window_seconds") val restartWindowSeconds: Long,
    @SerialName("jvm_metrics_poll_interval_seconds") val jvmMetricsPollIntervalSeconds: Int,
    @SerialName("rate_limit_login_per_minute") val rateLimitLoginPerMinute: Int,
    @SerialName("rate_limit_refresh_per_minute") val rateLimitRefreshPerMinute: Int,
    @SerialName("rate_limit_totp_verify_per_minute") val rateLimitTotpVerifyPerMinute: Int,
    @SerialName("image_minecraft") val imageMinecraft: String,
    @SerialName("image_proxy") val imageProxy: String,
    @SerialName("console_tail_lines") val consoleTailLines: Int,
    @SerialName("dns_domain_suffix") val dnsDomainSuffix: String?,
    @SerialName("dns_zone_id") val dnsZoneId: String?,
    @SerialName("dns_provider") val dnsProvider: String,
    // Read-only: true when a non-blank `cf_api_token` row exists. The token itself is never
    // part of this snapshot — it is only ever read (and decrypted) by [DnsProviderResolver].
    @SerialName("cf_api_token_set") val cfApiTokenSet: Boolean,
    @SerialName("metrics_poll_interval_seconds") val metricsPollIntervalSeconds: Int,
    @SerialName("metrics_collection_concurrency") val metricsCollectionConcurrency: Int,
    @SerialName("agent_reconcile_interval_seconds") val agentReconcileIntervalSeconds: Int
) {

    companion object {

        fun from(rows: List<SettingsEntry>): Settings {
            val map = rows.associate { it.key to it.value }
            return Settings(
                appName = map["app_name"]?.takeIf { it.isNotBlank() } ?: "CraftPanel",
                appLogo = map["app_logo"]?.takeIf { it.isNotBlank() },
                metricRetentionDays = map["metric_retention_days"]?.toIntOrNull() ?: 30,
                defaultBackupMaxCount = map["default_backup_max_count"]?.toIntOrNull() ?: 10,
                defaultPortRangeStart = map["default_port_range_start"]?.toIntOrNull() ?: 25570,
                defaultPortRangeEnd = map["default_port_range_end"]?.toIntOrNull() ?: 26070,
                restartMaxAttempts = map["restart_max_attempts"]?.toIntOrNull() ?: 5,
                restartWindowSeconds = map["restart_window_seconds"]?.toLongOrNull() ?: 600L,
                jvmMetricsPollIntervalSeconds = map["jvm_metrics_poll_interval_seconds"]?.toIntOrNull() ?: 30,
                rateLimitLoginPerMinute = map["rate_limit_login_per_minute"]?.toIntOrNull() ?: 10,
                rateLimitRefreshPerMinute = map["rate_limit_refresh_per_minute"]?.toIntOrNull() ?: 30,
                rateLimitTotpVerifyPerMinute = map["rate_limit_totp_verify_per_minute"]?.toIntOrNull() ?: 10,
                imageMinecraft = map["image_minecraft"] ?: "itzg/minecraft-server",
                imageProxy = map["image_proxy"] ?: "itzg/mc-proxy",
                consoleTailLines = map["console_tail_lines"]?.toIntOrNull() ?: 200,
                dnsDomainSuffix = map["dns_domain_suffix"]?.takeIf { it.isNotBlank() },
                dnsZoneId = map["dns_zone_id"]?.takeIf { it.isNotBlank() },
                dnsProvider = map["dns_provider"]?.takeIf { it.isNotBlank() } ?: "none",
                cfApiTokenSet = map["cf_api_token"]?.isNotBlank() == true,
                metricsPollIntervalSeconds = map["metrics_poll_interval_seconds"]?.toIntOrNull() ?: 5,
                metricsCollectionConcurrency = map["metrics_collection_concurrency"]?.toIntOrNull() ?: 8,
                agentReconcileIntervalSeconds = map["agent_reconcile_interval_seconds"]?.toIntOrNull() ?: 30
            )
        }
    }
}
