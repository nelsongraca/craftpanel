package io.craftpanel.master.service

import io.craftpanel.master.service.repo.*
import kotlin.uuid.Uuid

/**
 * The one module that answers "what is a server's hostname?" — managed hostname,
 * mc-router label, canonical hostname, global DNS resolution, and custom-hostname
 * validation.
 */
class ServerHostnames(private val settingsProvider: SettingsProvider, private val serverRepository: ServerRepository) {

    /** the global (zoneId, suffix), null if either is unconfigured. */
    fun resolveGlobalDns(): NetworkDns? {
        val settings = settingsProvider.current()
        val zoneId = settings.dnsZoneId ?: return null
        val suffix = settings.dnsDomainSuffix ?: return null
        return NetworkDns(zoneId, suffix)
    }

    /** the global domain suffix, or null if unconfigured. */
    fun resolveSuffix(): String? = settingsProvider.current().dnsDomainSuffix

    /** managed hostname for an exposed server (subdomain.suffix), or null. */
    fun managedHostname(row: ServerView): String? {
        if (!row.exposedExternally || row.publicSubdomain == null) return null
        return row.dnsRecordName ?: resolveSuffix()?.let { "${row.publicSubdomain}.$it" }
    }

    /** the mc-router label: managed + all custom hostnames comma-joined, or null. */
    fun mcRouterLabel(row: ServerView): String? {
        val parts = listOfNotNull(managedHostname(row)) + row.customHostnames()
        return if (parts.isEmpty()) null else parts.joinToString(",")
    }

    /** the canonical hostname shown in the API (first custom takes precedence, else managed). */
    fun canonicalHostname(row: ServerView): String? = row.customHostnames().firstOrNull() ?: managedHostname(row)

    /**
     * Parses a raw comma-separated custom-hostname list, validates every entry, and returns the
     * normalized comma-joined value — or null when the list is empty.
     */
    fun resolveCustomHostnames(raw: String?, excludeServerId: Uuid): String? {
        val hostnames = parseCustomHostnames(raw)
        if (hostnames.isEmpty()) return null
        hostnames.forEach { validateCustomHostname(it, excludeServerId) }
        return hostnames.joinToString(",")
    }

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
