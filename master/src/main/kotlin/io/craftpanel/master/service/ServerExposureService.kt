package io.craftpanel.master.service

import io.craftpanel.master.database.entity.Server
import io.craftpanel.master.domain.DesiredStatus
import io.craftpanel.master.dns.DnsProvider
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.service.repo.NodeRepository
import io.craftpanel.master.service.repo.ServerRepository
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import kotlin.uuid.Uuid

class ServerExposureService(
    private val dnsProvider: DnsProvider?,
    private val lifecycle: ContainerLifecycle,
    private val serverRepository: ServerRepository,
    private val nodeRepository: NodeRepository,
    private val serverExposure: ServerExposure
) {

    private val log = LoggerFactory.getLogger(ServerExposureService::class.java)

    fun updateExposure(id: Uuid, exposedExternally: Boolean, publicSubdomain: String?, customHostname: String?) {
        val serverRow = serverRepository.findById(id) ?: throw NotFoundException("Server not found")

        if (exposedExternally && publicSubdomain != null) {
            val existing = serverRepository.findBySubdomain(publicSubdomain)
            if (existing != null && existing.id != id) throw UnprocessableException("Public subdomain already taken")
        }

        val resolvedCustomHostname: String? = when {
            // Disabling exposure unsets the custom hostname: it is an mc-router routing name, so it
            // must not outlive the exposure it belongs to. Ignore any value still sent with the
            // request (the UI keeps the field's previous value in state when the box is unchecked).
            !exposedExternally -> null
            customHostname != null -> {
                val ch = customHostname.trim()
                if (ch.isEmpty()) {
                    null
                } else {
                    serverExposure.validateCustomHostname(ch, id)
                    ch
                }
            }
            else -> serverRow.customHostname
        }

        val existingRecordId = serverRow.dnsRecordId
        var newHostname: String? = null
        var newRecordId: String? = null

        if (exposedExternally && publicSubdomain != null) {
            val provider = dnsProvider
            val dns = serverExposure.resolveGlobalDns()

            if (provider != null && dns == null) {
                throw UnprocessableException(
                    "No DNS zone configured (set dns_zone_id and dns_domain_suffix in System Settings)"
                )
            }

            val fullHostname = if (dns != null) {
                "$publicSubdomain.${dns.domainSuffix}"
            } else {
                serverExposure.resolveSuffix()
                    ?.let { "$publicSubdomain.$it" }
            }

            newRecordId = if (provider != null && dns != null) {
                val node = nodeRepository.findById(serverRow.nodeId)
                    ?: throw BadGatewayException("Node not found")
                runCatching {
                    if (existingRecordId != null) {
                        provider.updateARecord(dns.zoneId, existingRecordId, node.publicIp)
                        existingRecordId
                    } else {
                        provider.createARecord(dns.zoneId, fullHostname ?: publicSubdomain, node.publicIp)
                    }
                }.getOrElse { ex -> throw BadGatewayException("DNS provider error: ${ex.message}") }
            } else {
                null
            }

            newHostname = fullHostname
        }

        if (!exposedExternally && existingRecordId != null && dnsProvider != null) {
            val dns = serverExposure.resolveGlobalDns()
            if (dns != null) {
                runCatching { dnsProvider!!.deleteARecord(dns.zoneId, existingRecordId) }
                    .onFailure { log.warn("Failed to delete DNS record $existingRecordId — continuing", it) }
            }
        }

        val resolvedPublicSubdomain = if (exposedExternally) publicSubdomain else null

        transaction {
            val e = Server.findById(id) ?: return@transaction
            e.exposedExternally = exposedExternally
            e.publicSubdomain = resolvedPublicSubdomain
            e.customHostname = resolvedCustomHostname
            e.dnsRecordId = if (exposedExternally && publicSubdomain != null) {
                newRecordId
            } else if (!exposedExternally) {
                null
            } else {
                existingRecordId
            }
            e.dnsRecordName = if (exposedExternally && publicSubdomain != null) {
                newHostname
            } else if (!exposedExternally) {
                null
            } else {
                serverRow.dnsRecordName
            }
        }

        val currentStatus = ServerStatus.fromDb(serverRow.status)
        if (currentStatus.isRunning) {
            val freshRow = serverRepository.findById(id)!!
            // Restart only when the mc-router routing names actually changed — exposing, disabling,
            // or editing a hostname. Disabling clears the custom hostname, so the label goes null
            // and the stale `mc-router.host` label is dropped on recreate. In desired-state model
            // this is a restart-envelope; the convergence loop owns the transition.
            if (serverExposure.mcRouterLabel(serverRow) != serverExposure.mcRouterLabel(freshRow)) {
                lifecycle.sendDesiredState(freshRow, DesiredStatus.RUNNING, forceRestart = true, publicHostname = serverExposure.mcRouterLabel(freshRow))
            }
        }
    }
}
