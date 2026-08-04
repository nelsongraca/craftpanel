package io.craftpanel.master.service

import io.craftpanel.master.service.repo.*
import kotlin.uuid.Uuid

/**
 * The one module that answers "what is a server's hostname?" — managed hostname,
 * mc-router label, canonical hostname, global DNS resolution, and custom-hostname
 * validation.
 */
class ServerExposure(private val settingsRepository: SettingsRepository, private val serverRepository: ServerRepository) {

    /** the global (zoneId, suffix), null if either is unconfigured. */
    fun resolveGlobalDns(): NetworkDns? {
        val settings = settingsRepository.getAll().associate { it.key to it.value }
        val zoneId = settings["dns_zone_id"]?.takeIf { it.isNotBlank() } ?: return null
        val suffix = settings["dns_domain_suffix"]?.takeIf { it.isNotBlank() } ?: return null
        return NetworkDns(zoneId, suffix)
    }

    /** the global domain suffix, or null if unconfigured. */
    fun resolveSuffix(): String? = settingsRepository.getAll()
        .firstOrNull { it.key == "dns_domain_suffix" }?.value?.takeIf { it.isNotBlank() }

    /** managed hostname for an exposed server (subdomain.suffix), or null. */
    fun managedHostname(row: ServerRow): String? {
        if (!row.exposedExternally || row.publicSubdomain == null) return null
        return row.dnsRecordName ?: resolveSuffix()?.let { "${row.publicSubdomain}.$it" }
    }

    /** the mc-router label: managed + custom hostnames comma-joined, or null. */
    fun mcRouterLabel(row: ServerRow): String? {
        val parts = listOfNotNull(managedHostname(row), row.customHostname)
        return if (parts.isEmpty()) null else parts.joinToString(",")
    }

    /** the canonical hostname shown in the API (custom takes precedence). */
    fun canonicalHostname(row: ServerRow): String? = row.customHostname ?: managedHostname(row)

    /** RFC-1123 validation + collision checks against managed/custom names + suffixes. */
    fun validateCustomHostname(hostname: String, excludeServerId: Uuid) {
        val rfc1123Label = Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$")
        val labels = hostname.split(".")
        if (labels.isEmpty() || labels.any { !it.matches(rfc1123Label) }) {
            throw UnprocessableException("custom_hostname must be a valid RFC-1123 hostname (e.g. play.yourdomain.com)")
        }

        val customTaken = serverRepository.findByCustomHostname(hostname)
        if (customTaken != null && customTaken.id != excludeServerId) {
            throw UnprocessableException("custom_hostname is already in use by another server")
        }

        val managedTaken = serverRepository.findByDnsRecordName(hostname)
        if (managedTaken != null && managedTaken.id != excludeServerId) {
            throw UnprocessableException("custom_hostname conflicts with a managed DNS record name")
        }

        val suffix = resolveSuffix()
        if (suffix != null && (hostname.endsWith(".$suffix") || hostname == suffix)) {
            throw UnprocessableException(
                "custom_hostname must not be under the panel-managed domain suffix ($suffix). " +
                    "Use the managed subdomain path instead."
            )
        }
    }

    data class NetworkDns(val zoneId: String, val domainSuffix: String)
}
