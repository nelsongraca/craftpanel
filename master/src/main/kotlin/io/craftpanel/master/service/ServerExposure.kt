package io.craftpanel.master.service

import io.craftpanel.master.service.repo.*
import kotlin.uuid.Uuid

/**
 * The one module that answers "what is a server's hostname?" — managed hostname,
 * mc-router label, canonical hostname, network→DNS resolution, and custom-hostname
 * validation.
 */
class ServerExposure(private val networkRepository: NetworkRepository, private val settingsRepository: SettingsRepository, private val serverRepository: ServerRepository) {

    /** network → (zoneId, suffix), null if the network has no DNS zone. */
    fun resolveNetworkDns(networkId: Uuid?): NetworkDns? {
        networkId ?: return null
        val row = networkRepository.findById(networkId) ?: return null
        val zoneId = row.cfZoneId ?: return null
        val suffix = row.cfDomainSuffix ?: return null
        return NetworkDns(zoneId, suffix)
    }

    /** the domain suffix for a network, falling back to the global setting. */
    fun resolveSuffix(networkId: Uuid?): String? = networkId?.let { networkRepository.findById(it)?.cfDomainSuffix }
        ?: settingsRepository.getAll()
            .firstOrNull { it.key == "dns_domain_suffix" }?.value

    /** managed hostname for an exposed server (subdomain.suffix), or null. */
    fun managedHostname(row: ServerRow): String? {
        if (!row.exposedExternally || row.publicSubdomain == null) return null
        return row.dnsRecordName ?: resolveSuffix(row.networkId)?.let { "${row.publicSubdomain}.$it" }
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

        val managedSuffixes = collectManagedSuffixes()
        for (suffix in managedSuffixes) {
            if (hostname.endsWith(".$suffix") || hostname == suffix) {
                throw UnprocessableException(
                    "custom_hostname must not be under a panel-managed domain suffix ($suffix). " +
                        "Use the managed subdomain path instead."
                )
            }
        }
    }

    private fun collectManagedSuffixes(): Set<String> {
        val suffixes = mutableSetOf<String>()
        networkRepository.listAll()
            .mapNotNull { it.cfDomainSuffix }
            .forEach { suffixes += it }
        settingsRepository.getAll()
            .firstOrNull { it.key == "dns_domain_suffix" }?.value?.let { suffixes += it }
        return suffixes
    }

    data class NetworkDns(val zoneId: String, val domainSuffix: String)
}
