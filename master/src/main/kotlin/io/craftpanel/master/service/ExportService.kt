package io.craftpanel.master.service

import io.craftpanel.master.database.entity.EnvVar
import io.craftpanel.master.database.entity.Mod
import io.craftpanel.master.database.entity.ProxyBackend
import io.craftpanel.master.database.entity.Server
import io.craftpanel.master.database.schema.ServerEnvVars
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.routes.dto.*
import io.craftpanel.master.service.repo.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import kotlin.uuid.Uuid
import java.time.Instant

class ExportService(
    private val serverRepository: ServerRepository,
    private val networkRepository: NetworkRepository,
    private val envVarsRepository: EnvVarsRepository,
    private val modRepository: ModRepository,
    private val extraPortRepository: ServerExtraPortRepository,
    private val proxyBackendRepository: ProxyBackendRepository,
    private val serverService: ServerService,
    private val networkService: NetworkService,
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
                )
            }
        val proxyBackends = if (row.serverType.isProxy) {
            proxyBackendRepository.listProxyBackends(serverId)
                .map { ProxyBackendExportItem(backendServerId = it.backendServerId.toString(), backendName = it.backendName, order = it.order) }
        }
        else null

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
            cpuShares = row.cpuShares,
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
            forwardingSecretEnc = row.forwardingSecretEnc,
            backupSchedule = row.backupSchedule,
            backupMaxCount = row.backupMaxCount,
            envVars = envVars,
            extraPorts = extraPorts,
            mods = mods,
            proxyBackends = proxyBackends,
        )
    }

    fun importServer(data: ServerExportData, nodeId: Uuid, networkId: Uuid?): ServerRow {
        val created = serverService.createServer(
            name = data.name,
            displayName = data.displayName,
            description = data.description,
            nodeId = nodeId.toString(),
            networkId = networkId?.toString(),
            serverType = data.serverType,
            mcVersion = data.mcVersion,
            itzgImageTag = data.itzgImageTag,
            memoryMb = data.memoryMb,
            cpuShares = data.cpuShares,
            expiresAt = data.expiresAt,
            customServerJar = data.customServerJar,
            containerListenPort = data.containerListenPort,
            containerProtocol = data.containerProtocol,
            disableHealthcheck = data.disableHealthcheck,
            forceRedownload = data.forceRedownload,
        )

        transaction {
            val e = Server.findById(created.id) ?: return@transaction
            e.configMode = data.configMode
            e.stopCommand = data.stopCommand
            if (data.exposedExternally != null) e.exposedExternally = data.exposedExternally
            if (data.customHostname != null) e.customHostname = data.customHostname
            if (data.proxyMotd != null) e.proxyMotd = data.proxyMotd
            if (data.proxyMaxPlayers != null) e.proxyMaxPlayers = data.proxyMaxPlayers
            if (data.proxyForwardingMode != null) e.proxyForwardingMode = data.proxyForwardingMode
            if (data.forwardingSecretEnc != null) e.forwardingSecretEnc = data.forwardingSecretEnc
            if (data.backupSchedule != null) e.backupSchedule = data.backupSchedule
            if (data.backupMaxCount != null) e.backupMaxCount = data.backupMaxCount
        }

        transaction {
            EnvVar.find { ServerEnvVars.serverId eq created.id }
                .forEach { it.delete() }
            data.envVars?.forEach { ev ->
                EnvVar.new {
                    this.serverId = EntityID(created.id, Servers)
                    key = ev.key
                    value = ev.value
                }
            }
        }

        data.mods?.forEach { mod ->
            transaction {
                Mod.new {
                    this.serverId = EntityID(created.id, Servers)
                    this.modrinthProjectId = mod.modrinthProjectId
                    this.displayName = mod.displayName
                    this.pinStrategy = mod.pinStrategy
                    this.pinnedVersionId = mod.pinnedVersionId
                }
            }
        }

        data.extraPorts?.forEach { port ->
            runCatching {
                extraPortRepository.createExtraPort(
                    serverId = created.id,
                    nodeId = created.nodeId,
                    name = port.name,
                    containerPort = port.containerPort,
                    hostPort = null,
                    protocol = port.protocol,
                )
            }.onFailure { log.warn("Failed to import extra port {} for server {}: {}", port.name, created.id, it.message) }
        }

        data.proxyBackends?.forEach { backend ->
            runCatching {
                transaction {
                    ProxyBackend.new {
                        this.proxyServerId = EntityID(created.id, Servers)
                        this.backendServerId = EntityID(created.id, Servers)
                        this.backendName = backend.backendName
                        this.order = backend.order
                    }
                }
            }.onFailure { log.warn("Failed to import proxy backend {} for server {}: {}", backend.backendName, created.id, it.message) }
        }

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
            servers = servers,
        )
    }

    fun importNetwork(data: NetworkExportData, nodeAssignments: Map<String, String>): NetworkResponse {
        val netResponse = networkService.createNetwork(
            CreateNetworkRequest(
                name = data.name,
                proxyPort = data.proxyPort,
                description = data.description,
            )
        )

        val networkId = Uuid.parse(netResponse.id)
        data.servers?.forEach { serverData ->
            val nodeIdStr = nodeAssignments[serverData.name] ?: throw UnprocessableException("No node assignment for server '${serverData.name}'")
            val nodeId = runCatching { Uuid.parse(nodeIdStr) }.getOrNull()
                ?: throw UnprocessableException("Invalid node_id '$nodeIdStr' for server '${serverData.name}'")
            importServer(serverData, nodeId, networkId)
        }

        val row = networkRepository.findById(networkId) ?: throw NotFoundException("Network not found")
        val serverCount = data.servers?.size ?: 0
        return NetworkResponse(
            id = row.id.toString(),
            name = row.name,
            proxyPort = row.proxyPort,
            description = row.description,
            serverCount = serverCount,
            createdAt = row.createdAt,
        )
    }
}
