package io.craftpanel.master.service

import io.craftpanel.master.database.entity.EnvVar
import io.craftpanel.master.database.entity.Mod
import io.craftpanel.master.database.entity.Server
import io.craftpanel.master.database.schema.Backups
import io.craftpanel.master.database.schema.ContainerMetrics
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.PortRegistry
import io.craftpanel.master.database.schema.ProxyBackends
import io.craftpanel.master.database.schema.ServerEnvVars
import io.craftpanel.master.database.schema.ServerJobs
import io.craftpanel.master.database.schema.ServerMigrations
import io.craftpanel.master.database.schema.ServerMods
import io.craftpanel.master.database.schema.ServerNetworks
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.dns.DnsProvider
import io.craftpanel.master.domain.DesiredStatus
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.domain.synthesizeStatus
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
import io.craftpanel.master.util.parseUtcInstant
import io.craftpanel.proto.masterMessage
import io.craftpanel.proto.removeContainerCommand
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import kotlin.uuid.Uuid

class ServerService(
    private val gateway: AgentGateway,
    private val networkService: NetworkService? = null,
    private val dnsProvider: DnsProvider? = null,
    private val containerNamePrefix: String = "craftpanel",
    private val serverRepository: ServerRepository,
    private val nodeRepository: NodeRepository,
    private val networkRepository: NetworkRepository,
    private val settingsRepository: SettingsRepository
) {

    private val log = LoggerFactory.getLogger(ServerService::class.java)
    private val capacityChecker = ResourceCapacityChecker(serverRepository)

    fun updateServer(
        id: Uuid,
        displayName: String?,
        description: String?,
        networkId: String?,
        mcVersion: String?,
        itzgImageTag: String?,
        customServerJar: String? = null,
        containerListenPort: Int? = null,
        containerProtocol: String? = null,
        disableHealthcheck: Boolean? = null,
        forceRedownload: Boolean? = null
    ) {
        val newNetworkId: Uuid? = networkId?.ifEmpty { null }
            ?.let { parseUuid(it) ?: throw UnprocessableException("Invalid network_id") }

        val serverRow = serverRepository.findById(id) ?: throw NotFoundException("Server not found")
        val serverType = serverRow.serverType
        if (serverType.isCustom && customServerJar != null && customServerJar.isBlank()) {
            throw UnprocessableException("custom_server_jar cannot be empty for CUSTOM server type")
        }
        if (containerPortProvidedOrInvalid(containerListenPort)) {
            throw UnprocessableException("container_listen_port must be between 1 and 65535")
        }
        if (containerProtocol != null) {
            validateContainerProtocol(containerProtocol)
        }

        if (newNetworkId != null) {
            val existingNodeIds = serverRepository.listByNetworkId(newNetworkId)
                .filter { it.id != id }
                .map { it.nodeId }
                .distinct()
            val allNodeIds = (existingNodeIds + serverRow.nodeId).distinct()
            if (allNodeIds.size > 1) networkService?.validateCrossNodeAssignment(allNodeIds)
            if (networkRepository.findById(newNetworkId) == null) {
                throw UnprocessableException("Network not found")
            }
        }

        transaction {
            val e = Server.findById(id) ?: return@transaction
            if (networkId != null && newNetworkId == null) {
                e.networkId = null
            }
            if (displayName != null) e.displayName = displayName
            val cleanDesc = description?.ifEmpty { null }
            if (cleanDesc != null) e.description = cleanDesc
            if (newNetworkId != null) e.networkId = EntityID(newNetworkId, ServerNetworks)
            if (mcVersion != null) e.mcVersion = mcVersion
            if (itzgImageTag != null) e.itzgImageTag = itzgImageTag
            if (customServerJar != null) e.customServerJar = customServerJar
            if (containerListenPort != null) e.containerListenPort = containerListenPort
            if (containerProtocol != null) e.containerProtocol = validateContainerProtocol(containerProtocol)
            if (disableHealthcheck != null) e.disableHealthcheck = disableHealthcheck
            if (forceRedownload != null) e.forceRedownload = forceRedownload
        }
    }

    private fun containerPortProvidedOrInvalid(containerListenPort: Int?): Boolean = containerListenPort != null && (containerListenPort <= 0 || containerListenPort > 65535)

    fun deleteServer(id: Uuid) {
        val existing = serverRepository.findById(id) ?: throw NotFoundException("Server not found")
        // Guard on the synthesized status, not the reported one: a start request sets desired=RUNNING
        // before the agent has reported anything, so a "STARTING" server (reported STOPPED) must not
        // be deletable.
        val displayed = synthesizeStatus(DesiredStatus.fromDb(existing.desiredStatus), ServerStatus.fromDb(existing.status))
        if (displayed != ServerStatus.STOPPED) throw ConflictException("Server must be STOPPED before deletion")

        val recordId = existing.dnsRecordId
        if (recordId != null) {
            val provider = dnsProvider
                ?: throw ConflictException("Cannot delete server with DNS record: DNS provider not configured")
            val settings = settingsRepository.getAll()
                .associate { it.key to it.value }
            val zoneId = settings["dns_zone_id"]?.takeIf { it.isNotBlank() }
                ?: throw ConflictException("Cannot delete server with DNS record: no DNS zone configured")
            runCatching { provider.deleteARecord(zoneId, recordId) }
                .onFailure { log.warn("Failed to delete DNS record $recordId during server delete", it) }
        }

        val nodeId = existing.nodeId.toString()
        gateway.sendToNode(
            nodeId,
            masterMessage {
                removeContainer = removeContainerCommand {
                    serverId = id.toString()
                    containerName = "$containerNamePrefix-$id"
                    force = true
                    deleteData = true
                    serverName = existing.name
                }
            }
        )

        transaction {
            PortRegistry.deleteWhere { PortRegistry.serverId eq id }
            ServerMods.deleteWhere { ServerMods.serverId eq id }
            ServerJobs.deleteWhere { ServerJobs.serverId eq id }
            ServerMigrations.deleteWhere { ServerMigrations.serverId eq id }
            ServerEnvVars.deleteWhere { ServerEnvVars.serverId eq id }
            Backups.deleteWhere { Backups.serverId eq id }
            ContainerMetrics.deleteWhere { ContainerMetrics.serverId eq id }
            ProxyBackends.deleteWhere { ProxyBackends.proxyServerId eq id }
            ProxyBackends.deleteWhere { ProxyBackends.backendServerId eq id }
            Server.findById(id)
                ?.delete()
        }
    }

    fun updateResources(id: Uuid, memoryMb: Int, cpuShares: Int, itzgImageTag: String?) {
        if (memoryMb <= 0) throw UnprocessableException("memory_mb must be positive")
        if (cpuShares < 0) throw UnprocessableException("cpu_shares must be non-negative")
        val existing = serverRepository.findById(id) ?: throw NotFoundException("Server not found")
        val nodeKotlinId = existing.nodeId
        val node = nodeRepository.findById(nodeKotlinId) ?: throw UnprocessableException("Node not found")
        when (capacityChecker.check(node, excludeServerId = id, memoryMb = memoryMb, cpuShares = cpuShares)) {
            CapacityResult.InsufficientRam -> throw ConflictException("Insufficient RAM capacity on node")
            CapacityResult.InsufficientCpu -> throw ConflictException("Insufficient CPU capacity on node")
            CapacityResult.Ok -> {}
        }
        transaction {
            val e = Server.findById(id) ?: return@transaction
            e.memoryMb = memoryMb
            e.cpuShares = cpuShares
            if (itzgImageTag != null) e.itzgImageTag = itzgImageTag
        }
    }

    fun updateExpiration(id: Uuid, expiresAt: String?): ServerView {
        serverRepository.findById(id) ?: throw NotFoundException("Server not found")
        val expiryLocal = parseExpiresAt(expiresAt)
        transaction {
            Server.findById(id)?.let { it.expiresAt = expiryLocal }
        }
        return serverRepository.findById(id) ?: throw NotFoundException("Server not found")
    }

    fun updateDisabled(id: Uuid, disabled: Boolean): ServerView {
        serverRepository.findById(id) ?: throw NotFoundException("Server not found")
        transaction {
            Server.findById(id)?.let { it.disabled = disabled }
        }
        return serverRepository.findById(id) ?: throw NotFoundException("Server not found")
    }
}
