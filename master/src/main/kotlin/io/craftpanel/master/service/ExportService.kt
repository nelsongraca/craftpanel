package io.craftpanel.master.service

import io.craftpanel.master.database.entity.ProxyBackend
import io.craftpanel.master.database.schema.ProxyBackends
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.routes.dto.*
import io.craftpanel.master.service.repo.EnvVarsRepository
import io.craftpanel.master.service.repo.ModRepository
import io.craftpanel.master.service.repo.NetworkRepository
import io.craftpanel.master.service.repo.ProxyBackendRepository
import io.craftpanel.master.service.repo.ServerExtraPortRepository
import io.craftpanel.master.service.repo.ServerRepository
import io.craftpanel.master.service.repo.ServerView
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.uuid.Uuid

class ExportService(
    private val serverRepository: ServerRepository,
    private val networkRepository: NetworkRepository,
    private val envVarsRepository: EnvVarsRepository,
    private val modRepository: ModRepository,
    private val extraPortRepository: ServerExtraPortRepository,
    private val proxyBackendRepository: ProxyBackendRepository,
    private val provisioning: ServerProvisioning,
    private val networkService: NetworkService
) {

    private val log = LoggerFactory.getLogger(ExportService::class.java)

    fun exportServer(serverId: Uuid): ServerExportData {
        val row = serverRepository.findById(serverId) ?: throw NotFoundException("Server not found")
        val envVars = envVarsRepository.getEnvVars(serverId)
            .map { EnvVarExportItem(key = it.key, value = it.value) }
        val extraPorts = extraPortRepository.findByServerId(serverId)
            .map { ExtraPortExportItem(name = it.name, containerPort = it.containerPort, protocol = it.protocol) }
        val mods = modRepository.listMods(serverId)
            .map {
                ModExportItem(
                    modrinthProjectId = it.modrinthProjectId,
                    displayName = it.displayName,
                    pinStrategy = it.pinStrategy,
                    pinnedVersionId = it.pinnedVersionId,
                    enabled = it.enabled
                )
            }
        val proxyBackends = if (row.serverType.isProxy) {
            proxyBackendRepository.listProxyBackends(serverId)
                .map {
                    ProxyBackendExportItem(
                        backendServerId = it.backendServerId.toString(),
                        backendServerName = serverRepository.findById(it.backendServerId)?.name,
                        backendName = it.backendName,
                        order = it.order
                    )
                }
        } else {
            null
        }

        return ServerExportData(
            exportedAt = Instant.now()
                .toString(),
            name = row.name,
            displayName = row.displayName,
            description = row.description,
            serverType = row.serverType.toDb(),
            mcVersion = row.mcVersion,
            itzgImageTag = row.itzgImageTag,
            memoryMb = row.memoryMb,
            cpuLimitMillicores = row.cpuLimitMillicores,
            exposedExternally = row.exposedExternally,
            customHostname = row.customHostname,
            configMode = row.configMode,
            stopCommand = row.stopCommand,
            customServerJar = row.customServerJar,
            containerListenPort = row.containerListenPort,
            containerProtocol = row.containerProtocol,
            disableHealthcheck = row.disableHealthcheck,
            forceRedownload = row.forceRedownload,
            expiresAt = row.expiresAt,
            proxyMotd = row.proxyMotd,
            proxyMaxPlayers = row.proxyMaxPlayers,
            proxyForwardingMode = row.proxyForwardingMode,
            proxyProtocol = row.proxyProtocol,
            forwardingSecretEnc = row.forwardingSecretEnc,
            forwardingPatchFile = row.forwardingPatchFile,
            backupSchedule = row.backupSchedule,
            backupMaxCount = row.backupMaxCount,
            envVars = envVars,
            extraPorts = extraPorts,
            mods = mods,
            proxyBackends = proxyBackends
        )
    }

    fun importServer(data: ServerExportData, nodeId: Uuid, networkId: Uuid?): ServerView {
        val created = provisionFrom(data, nodeId, networkId)
        wireProxyBackends(created.id, data.proxyBackends)
        return serverRepository.findById(created.id) ?: throw NotFoundException("Server not found")
    }

    fun exportNetwork(networkId: Uuid): NetworkExportData {
        val row = networkRepository.findById(networkId) ?: throw NotFoundException("Network not found")
        val servers = serverRepository.listByNetworkId(networkId)
            .map { exportServer(it.id) }
        return NetworkExportData(
            exportedAt = Instant.now()
                .toString(),
            name = row.name,
            description = row.description,
            proxyPort = row.proxyPort,
            servers = servers
        )
    }

    fun importNetwork(data: NetworkExportData, nodeAssignments: Map<String, String>): NetworkResponse {
        val netResponse = networkService.createNetwork(
            CreateNetworkRequest(
                name = data.name,
                proxyPort = data.proxyPort,
                description = data.description
            )
        )

        val networkId = Uuid.parse(netResponse.id)
        // Pass 1: materialise every server. Proxy backends are wired in a second pass because a
        // backend server may appear later in the export.
        data.servers?.forEach { serverData ->
            val nodeIdStr = nodeAssignments[serverData.name] ?: throw UnprocessableException("No node assignment for server '${serverData.name}'")
            val nodeId = runCatching { Uuid.parse(nodeIdStr) }.getOrNull()
                ?: throw UnprocessableException("Invalid node_id '$nodeIdStr' for server '${serverData.name}'")
            provisionFrom(serverData, nodeId, networkId)
        }

        data.servers?.forEach { serverData ->
            val proxy = serverRepository.findByName(serverData.name) ?: return@forEach
            wireProxyBackends(proxy.id, serverData.proxyBackends)
        }

        val row = networkRepository.findById(networkId) ?: throw NotFoundException("Network not found")
        val serverCount = data.servers?.size ?: 0
        return NetworkResponse(
            id = row.id.toString(),
            name = row.name,
            proxyPort = row.proxyPort,
            description = row.description,
            serverCount = serverCount,
            createdAt = row.createdAt
        )
    }

    private fun provisionFrom(data: ServerExportData, nodeId: Uuid, networkId: Uuid?): ServerView {
        val spec = ServerProvisionSpec(
            name = data.name,
            displayName = data.displayName,
            description = data.description,
            nodeId = nodeId.toString(),
            networkId = networkId?.toString(),
            serverType = data.serverType,
            mcVersion = data.mcVersion,
            itzgImageTag = data.itzgImageTag,
            memoryMb = data.memoryMb,
            cpuLimitMillicores = data.cpuLimitMillicores,
            expiresAt = data.expiresAt,
            customServerJar = data.customServerJar,
            containerListenPort = data.containerListenPort,
            containerProtocol = data.containerProtocol,
            disableHealthcheck = data.disableHealthcheck,
            forceRedownload = data.forceRedownload,
            configMode = data.configMode,
            stopCommand = data.stopCommand,
            proxyMotd = data.proxyMotd,
            proxyMaxPlayers = data.proxyMaxPlayers,
            proxyForwardingMode = data.proxyForwardingMode,
            proxyProtocol = data.proxyProtocol,
            forwardingSecretEnc = data.forwardingSecretEnc,
            forwardingPatchFile = data.forwardingPatchFile,
            backupSchedule = data.backupSchedule,
            backupMaxCount = data.backupMaxCount,
            exposedExternally = data.exposedExternally,
            customHostname = data.customHostname,
            envVars = data.envVars?.associate { it.key to it.value },
            mods = data.mods?.map {
                ProvisionMod(
                    modrinthProjectId = it.modrinthProjectId,
                    displayName = it.displayName,
                    pinStrategy = it.pinStrategy,
                    pinnedVersionId = it.pinnedVersionId,
                    enabled = it.enabled
                )
            } ?: emptyList(),
            extraPorts = data.extraPorts?.map {
                ProvisionExtraPort(name = it.name, containerPort = it.containerPort, protocol = it.protocol)
            } ?: emptyList()
        )
        return provisioning.provision(spec)
    }

    /**
     * Wire a proxy's backends by resolving each backend's exported server name to the (now existing)
     * target server. Names are unique, so this survives id remapping across export/import. A backend
     * whose server is absent from the target panel is skipped with a warning rather than
     * self-referencing the proxy.
     */
    private fun wireProxyBackends(proxyServerId: Uuid, backends: List<ProxyBackendExportItem>?) {
        if (backends.isNullOrEmpty()) return
        transaction {
            backends.forEach { backend ->
                val backendId = backend.backendServerName
                    ?.let { serverRepository.findByName(it)?.id }
                if (backendId == null) {
                    log.warn(
                        "Skipping proxy backend '{}' for server {}: backend server '{}' not found",
                        backend.backendName,
                        proxyServerId,
                        backend.backendServerName
                    )
                    return@forEach
                }
                ProxyBackend.new {
                    this.proxyServerId = EntityID(proxyServerId, Servers)
                    this.backendServerId = EntityID(backendId, Servers)
                    this.backendName = backend.backendName
                    this.order = backend.order
                }
            }
        }
    }
}
