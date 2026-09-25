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
    private val dnsProvider: (() -> DnsProvider?)? = null,
    private val lifecycle: ContainerLifecycle,
    private val serverRepository: ServerRepository,
    private val nodeRepository: NodeRepository,
    private val serverHostnames: ServerHostnames
) {

    private val log = LoggerFactory.getLogger(ServerExposureService::class.java)

    suspend fun updateExposure(id: Uuid, exposedExternally: Boolean, publicSubdomain: String?, customHostname: String?) {
        val serverRow = serverRepository.findById(id) ?: throw NotFoundException("Server not found")
        val subdomain = publicSubdomain?.trim()?.takeIf { it.isNotEmpty() }

        if (exposedExternally && subdomain != null) {
            if (!SUBDOMAIN_LABEL.matches(subdomain)) {
                throw UnprocessableException("public_subdomain must be a single valid DNS label (e.g. survival)")
            }
            val existing = serverRepository.findBySubdomain(subdomain)
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

        // Whether the stored DNS record was actually removed (or there was nothing to remove). On a
        // disable whose delete fails we keep the id so a later enable adopts the existing record
        // instead of mistaking it for a foreign one.
        var recordCleared = existingRecordId == null

        if (exposedExternally && subdomain != null) {
            val provider = dnsProvider?.invoke()
            val dns = serverHostnames.resolveGlobalDns()

            if (provider != null && dns == null) {
                throw UnprocessableException(
                    "No DNS zone configured (set dns_zone_id and dns_domain_suffix in System Settings)"
                )
            }

            val fullHostname = if (dns != null) {
                "$subdomain.${dns.domainSuffix}"
            } else {
                serverHostnames.resolveSuffix()
                    ?.let { "$subdomain.$it" }
            }

            newRecordId = if (provider != null && dns != null) {
                val node = nodeRepository.findById(serverRow.nodeId)
                    ?: throw BadGatewayException("Node not found")
                if (node.publicIp.isBlank()) {
                    throw UnprocessableException("Node has no public IP configured; cannot create a DNS record")
                }
                val target = fullHostname ?: subdomain
                val nameChanged = serverRow.dnsRecordName != target

                if (existingRecordId != null && !nameChanged) {
                    // Our record, unchanged name — repoint it at this node's IP.
                    provider.updateARecord(dns.zoneId, existingRecordId, node.publicIp)
                    existingRecordId
                } else {
                    // First exposure or a rename. Never overwrite a record we did not create.
                    val found = provider.findARecord(dns.zoneId, target)
                    if (found != null && found.id != existingRecordId) {
                        throw UnprocessableException(
                            "A DNS record for $target already exists and was not created by CraftPanel; " +
                                "refusing to overwrite it"
                        )
                    }
                    val recordId = found?.id ?: provider.createARecord(dns.zoneId, target, node.publicIp)
                    // Rename: drop our old record once the new one is in place.
                    if (existingRecordId != null && existingRecordId != recordId) {
                        runCatching { provider.deleteARecord(dns.zoneId, existingRecordId) }
                            .onFailure { log.warn("Failed to delete old DNS record $existingRecordId during rename — continuing", it) }
                    }
                    recordId
                }
            } else {
                null
            }

            newHostname = fullHostname
        }

        val deleteProvider = dnsProvider?.invoke()
        if (!exposedExternally && existingRecordId != null && deleteProvider != null) {
            val dns = serverHostnames.resolveGlobalDns()
            if (dns != null) {
                runCatching { deleteProvider.deleteARecord(dns.zoneId, existingRecordId) }
                    .onSuccess { recordCleared = true }
                    .onFailure { log.warn("Failed to delete DNS record $existingRecordId — keeping it so a later enable can reuse it", it) }
            }
        }

        val resolvedPublicSubdomain = if (exposedExternally) subdomain else null

        transaction {
            val e = Server.findById(id) ?: return@transaction
            e.exposedExternally = exposedExternally
            e.publicSubdomain = resolvedPublicSubdomain
            e.customHostname = resolvedCustomHostname
            when {
                exposedExternally && subdomain != null -> {
                    e.dnsRecordId = newRecordId
                    e.dnsRecordName = newHostname
                }

                !exposedExternally -> {
                    if (recordCleared) {
                        e.dnsRecordId = null
                        e.dnsRecordName = null
                    }
                }

                else -> {
                    e.dnsRecordId = existingRecordId
                    e.dnsRecordName = serverRow.dnsRecordName
                }
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

    private companion object {
        /** A single RFC-1123 DNS label: the managed subdomain prefix (e.g. `survival`). */
        val SUBDOMAIN_LABEL = Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$")
    }
}
