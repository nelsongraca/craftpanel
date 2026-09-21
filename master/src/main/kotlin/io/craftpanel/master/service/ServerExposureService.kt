package io.craftpanel.master.service

import io.craftpanel.master.database.entity.Server
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
    private val serverHostnames: ServerHostnames
) {

    private val log = LoggerFactory.getLogger(ServerExposureService::class.java)

    fun updateExposure(id: Uuid, exposedExternally: Boolean, publicSubdomain: String?, customHostname: String?) {
        val serverRow = serverRepository.findById(id) ?: throw NotFoundException("Server not found")

        if (exposedExternally && publicSubdomain != null) {
            val existing = serverRepository.findBySubdomain(publicSubdomain)
            if (existing != null && existing.id != id) throw UnprocessableException("Public subdomain already taken")
        }

        val resolvedCustomHostname: String? = when {
            // Disabling exposure unsets the custom hostnames: they are mc-router routing names, so
            // they must not outlive the exposure they belong to. Ignore any value still sent with
            // the request (the UI keeps the field's previous value in state when the box is
            // unchecked).
            !exposedExternally -> null

            customHostname != null -> serverHostnames.resolveCustomHostnames(customHostname, id)

            else -> serverRow.customHostname
        }

        val existingRecordId = serverRow.dnsRecordId
        var newHostname: String? = null
        var newRecordId: String? = null

        if (exposedExternally && publicSubdomain != null) {
            val provider = dnsProvider
            val dns = serverHostnames.resolveGlobalDns()

            if (provider != null && dns == null) {
                throw UnprocessableException(
                    "No DNS zone configured (set dns_zone_id and dns_domain_suffix in System Settings)"
                )
            }

            val fullHostname = if (dns != null) {
                "$publicSubdomain.${dns.domainSuffix}"
            } else {
                serverHostnames.resolveSuffix()
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
            val dns = serverHostnames.resolveGlobalDns()
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
            // mc-router labels are baked in at container creation, so a routing-name change only
            // takes effect on the next start/restart. Flag a pending restart for the UI and refresh
            // the agent's stored spec — never yank a live server out from under its players.
            if (serverHostnames.mcRouterLabel(serverRow) != serverHostnames.mcRouterLabel(freshRow)) {
                transaction { Server.findById(id)?.let { it.restartPending = true } }
                lifecycle.refreshRunningSpec(freshRow, publicHostname = serverHostnames.mcRouterLabel(freshRow))
            }
        }
    }
}
