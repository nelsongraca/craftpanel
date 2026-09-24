package io.craftpanel.master.service

import io.craftpanel.common.ContainerNames
import io.craftpanel.common.ServerPaths
import io.craftpanel.master.database.entity.Server
import io.craftpanel.master.database.schema.ServerNetworks
import io.craftpanel.master.dns.DnsProvider
import io.craftpanel.master.domain.DesiredStatus
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.domain.synthesizeStatus
import io.craftpanel.master.service.repo.NetworkRepository
import io.craftpanel.master.service.repo.NodeRepository
import io.craftpanel.master.service.repo.ServerRepository
import io.craftpanel.master.service.repo.ServerView
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import kotlin.uuid.Uuid

class ServerService(
    private val gateway: AgentGateway,
    private val networkService: NetworkService? = null,
    private val dnsProvider: DnsProvider? = null,
    private val containerNamePrefix: String = ContainerNames.DEFAULT_PREFIX,
    private val serverRepository: ServerRepository,
    private val nodeRepository: NodeRepository,
    private val networkRepository: NetworkRepository,
    private val settingsProvider: SettingsProvider,
    private val lifecycle: ContainerLifecycle
) {

    private val log = LoggerFactory.getLogger(ServerService::class.java)
    private val names = ContainerNames(containerNamePrefix)
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
        forceRedownload: Boolean? = null,
        jvmMetricsEnabled: Boolean? = null
    ) {
        val newNetworkId: Uuid? = networkId?.ifEmpty { null }
            ?.let { parseUuid(it) ?: throw UnprocessableException("Invalid network_id") }

        // Fields that alter the container spec: saving any of them while running leaves the live
        // container stale until the next start/restart, so flag a pending restart for the UI.
        val specChanged = networkId != null || mcVersion != null || itzgImageTag != null ||
            customServerJar != null || containerListenPort != null || containerProtocol != null ||
            disableHealthcheck != null || forceRedownload != null || jvmMetricsEnabled != null

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
            networkService?.requireSingleNodeForNetwork(newNetworkId, serverRow.nodeId, excludeServerId = id)
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
            if (jvmMetricsEnabled != null) e.jvmMetricsEnabled = jvmMetricsEnabled
            if (specChanged) e.restartPending = true
        }
    }

    private fun containerPortProvidedOrInvalid(containerListenPort: Int?): Boolean = containerListenPort != null && (containerListenPort <= 0 || containerListenPort > 65535)

    /**
     * Admin override for the `servers/<name>` data directory. Null/blank clears the override.
     * No rename is performed — the directory must already hold the data (or be created empty on
     * next start). After saving, the node's override snapshot is re-pushed so even stopped servers
     * resolve the new path; a running container is flagged restart-pending and picks up the new bind
     * mount on the next start/restart (never restarted out from under players).
     */
    fun updateDataDirName(id: Uuid, dataDirName: String?): ServerView {
        val server = serverRepository.findById(id) ?: throw NotFoundException("Server not found")
        val clean = dataDirName?.trim()?.ifEmpty { null }
        if (clean != null) {
            if (!ServerPaths.isValidDataDirName(clean)) {
                throw UnprocessableException("Invalid data directory name")
            }
            val clash = serverRepository.listByNodeId(server.nodeId)
                .any { it.id != id && it.dataDirName == clean }
            if (clash) throw ConflictException("Data directory name already in use on this node")
        }

        val changed = server.dataDirName != clean
        transaction {
            Server.findById(id)?.let {
                it.dataDirName = clean
                if (changed) it.restartPending = true
            }
        }
        val updated = serverRepository.findById(id) ?: throw NotFoundException("Server not found")

        gateway.rebuildSymlinks(updated.nodeId.toString())
        if (DesiredStatus.fromDb(updated.desiredStatus) == DesiredStatus.RUNNING) {
            // Refresh the agent's stored spec (no force_restart): the bind mount applies on the next
            // start/restart or an autonomous crash-recreate, not immediately.
            lifecycle.refreshRunningSpec(updated)
        }
        return updated
    }

    suspend fun deleteServer(id: Uuid) {
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
            val zoneId = settingsProvider.current().dnsZoneId
                ?: throw ConflictException("Cannot delete server with DNS record: no DNS zone configured")
            runCatching { provider.deleteARecord(zoneId, recordId) }
                .onFailure { log.warn("Failed to delete DNS record $recordId during server delete", it) }
        }

        val nodeId = existing.nodeId.toString()
        gateway.sendToNode(
            nodeId,
            ContainerLifecycle.removeContainerMessage(
                containerName = names.container(id.toString()),
                serverId = id.toString(),
                force = true,
                deleteData = true,
                serverName = existing.name
            )
        )

        transaction {
            // Every child table (ports, mods, jobs, migrations, env vars, backups, container metrics,
            // proxy backends) declares ON DELETE CASCADE, so deleting the server row removes them.
            Server.findById(id)
                ?.delete()
        }
    }

    fun updateResources(id: Uuid, memoryMb: Int, cpuLimitMillicores: Int, itzgImageTag: String?) {
        if (memoryMb <= 0) throw UnprocessableException("memory_mb must be positive")
        if (cpuLimitMillicores < 0) throw UnprocessableException("cpu_limit_millicores must be non-negative")
        val existing = serverRepository.findById(id) ?: throw NotFoundException("Server not found")
        val nodeKotlinId = existing.nodeId
        val node = nodeRepository.findById(nodeKotlinId) ?: throw UnprocessableException("Node not found")
        when (capacityChecker.check(node, excludeServerId = id, memoryMb = memoryMb, cpuLimitMillicores = cpuLimitMillicores)) {
            CapacityResult.InsufficientRam -> throw ConflictException("Insufficient RAM capacity on node")
            CapacityResult.InsufficientCpu -> throw ConflictException("Insufficient CPU capacity on node")
            CapacityResult.Ok -> {}
        }
        transaction {
            val e = Server.findById(id) ?: return@transaction
            e.memoryMb = memoryMb
            e.cpuLimitMillicores = cpuLimitMillicores
            if (itzgImageTag != null) e.itzgImageTag = itzgImageTag
            e.restartPending = true
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
