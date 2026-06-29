package io.craftpanel.master.service

import io.craftpanel.master.dns.DnsProvider
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.service.repo.NetworkRepository
import io.craftpanel.master.service.repo.NodeRepository
import io.craftpanel.master.service.repo.ServerRepository
import io.craftpanel.master.service.repo.SettingsRepository
import org.slf4j.LoggerFactory
import kotlin.uuid.Uuid

class ServerExposureService(
    private val dnsProvider: DnsProvider?,
    private val lifecycle: ContainerLifecycle,
    private val serverRepository: ServerRepository,
    private val nodeRepository: NodeRepository,
    private val networkRepository: NetworkRepository,
    private val settingsRepository: SettingsRepository,
) {

    private val log = LoggerFactory.getLogger(ServerExposureService::class.java)

    fun updateExposure(id: Uuid, req: PatchExposureRequest) {
        val serverRow = serverRepository.findById(id) ?: throw NotFoundException("Server not found")

        if (req.exposedExternally && req.publicSubdomain != null) {
            val existing = serverRepository.findBySubdomain(req.publicSubdomain)
            if (existing != null && existing.id != id) throw UnprocessableException("Public subdomain already taken")
        }

        val resolvedCustomHostname: String? = if (req.customHostname != null) {
            val ch = req.customHostname.trim()
            if (ch.isEmpty()) null
            else {
                validateCustomHostname(ch, id)
                ch
            }
        }
        else serverRow.customHostname

        val existingRecordId = serverRow.dnsRecordId
        var newHostname: String? = null
        var newRecordId: String? = null

        if (req.exposedExternally && req.publicSubdomain != null) {
            val provider = dnsProvider
            val dns = resolveNetworkDns(serverRow.networkId)

            if (provider != null && dns == null) {
                throw UnprocessableException(
                    "Server's network has no DNS zone configured (set dns_zone_id and dns_domain_suffix on the network)"
                )
            }

            val fullHostname = if (dns != null) "${req.publicSubdomain}.${dns.domainSuffix}"
            else resolvePublicHostname(req.publicSubdomain, serverRow.networkId)

            newRecordId = if (provider != null && dns != null) {
                val node = nodeRepository.findById(serverRow.nodeId)
                    ?: throw BadGatewayException("Node not found")
                runCatching {
                    if (existingRecordId != null) {
                        provider.updateARecord(dns.zoneId, existingRecordId, node.publicIp)
                        existingRecordId
                    }
                    else {
                        provider.createARecord(dns.zoneId, fullHostname ?: req.publicSubdomain, node.publicIp)
                    }
                }.getOrElse { ex -> throw BadGatewayException("DNS provider error: ${ex.message}") }
            }
            else null

            newHostname = fullHostname
        }

        if (!req.exposedExternally && existingRecordId != null && dnsProvider != null) {
            val dns = resolveNetworkDns(serverRow.networkId)
            if (dns != null) {
                runCatching { dnsProvider!!.deleteARecord(dns.zoneId, existingRecordId) }
                    .onFailure { log.warn("Failed to delete DNS record $existingRecordId — continuing", it) }
            }
        }

        val prevCustomHostname = serverRow.customHostname
        val customHostnameChanged = resolvedCustomHostname != prevCustomHostname
        val exposureNeedsRecreate = req.publicSubdomain != null || customHostnameChanged

        serverRepository.updateExposure(
            id = id,
            exposedExternally = req.exposedExternally,
            publicSubdomain = if (!req.exposedExternally) null else req.publicSubdomain,
            customHostname = resolvedCustomHostname,
            dnsRecordId = if (req.exposedExternally && req.publicSubdomain != null) newRecordId
            else if (!req.exposedExternally) null else existingRecordId,
            dnsRecordName = if (req.exposedExternally && req.publicSubdomain != null) newHostname
            else if (!req.exposedExternally) null else serverRow.dnsRecordName,
            needsRecreate = if (exposureNeedsRecreate) true else null,
        )

        val currentStatus = ServerStatus.fromDb(serverRow.status)
        if (currentStatus.isRunning && exposureNeedsRecreate) {
            val freshRow = serverRepository.findById(id)!!
            serverRepository.updateStatus(id, "STARTING", null)
            lifecycle.sendStart(freshRow, needsRecreate = true, publicHostname = buildMcRouterLabel(freshRow, networkRepository, settingsRepository))
        }
    }

    private fun validateCustomHostname(hostname: String, excludeServerId: Uuid) {
        val rfc1123Label = Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$")
        val labels = hostname.split(".")
        if (labels.isEmpty() || labels.any { !it.matches(rfc1123Label) }) {
            throw UnprocessableException("custom_hostname must be a valid RFC-1123 hostname (e.g. play.yourdomain.com)")
        }

        val customTaken = serverRepository.findByCustomHostname(hostname)
        if (customTaken != null && customTaken.id != excludeServerId)
            throw UnprocessableException("custom_hostname is already in use by another server")

        val managedTaken = serverRepository.findByDnsRecordName(hostname)
        if (managedTaken != null && managedTaken.id != excludeServerId)
            throw UnprocessableException("custom_hostname conflicts with a managed DNS record name")

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

    private data class NetworkDns(val zoneId: String, val domainSuffix: String)

    private fun resolveNetworkDns(networkId: Uuid?): NetworkDns? {
        networkId ?: return null
        val row = networkRepository.findById(networkId) ?: return null
        val zoneId = row.cfZoneId ?: return null
        val suffix = row.cfDomainSuffix ?: return null
        return NetworkDns(zoneId, suffix)
    }

    private fun resolvePublicHostname(subdomain: String, networkId: Uuid?): String? {
        val suffix = networkId?.let { networkRepository.findById(it)?.cfDomainSuffix }
            ?: settingsRepository.getAll()
                .firstOrNull { it.key == "dns_domain_suffix" }?.value
        return suffix?.let { "$subdomain.$it" }
    }
}
