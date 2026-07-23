package io.craftpanel.master.service

import io.craftpanel.master.dns.DnsProvider
import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
import io.craftpanel.proto.masterMessage
import io.craftpanel.proto.removeContainerCommand
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class ContainerMetricsPoint(val t: String, val v: Double)

@Serializable
data class ContainerMetricsPointLong(val t: String, val v: Long)

@Serializable
data class ContainerMetricsSeriesResponse(@SerialName("server_id") val serverId: String, val series: ContainerMetricsSeries)

@Serializable
data class ContainerMetricsSeries(
    @SerialName("cpu_percent") val cpuPercent: List<ContainerMetricsPoint>,
    @SerialName("ram_used_mb") val ramUsedMb: List<ContainerMetricsPoint>,
    @SerialName("net_in_bytes") val netInBytes: List<ContainerMetricsPointLong>,
    @SerialName("net_out_bytes") val netOutBytes: List<ContainerMetricsPointLong>
)

class ServerService(
    private val gateway: AgentGateway,
    private val networkService: NetworkService? = null,
    private val dnsProvider: DnsProvider? = null,
    private val containerNamePrefix: String = "craftpanel",
    private val serverRepository: ServerRepository,
    private val nodeRepository: NodeRepository,
    private val networkRepository: NetworkRepository,
    private val userRepository: UserRepository,
    private val groupRepository: GroupRepository,
    private val settingsRepository: SettingsRepository,
    private val portRepository: PortRepository,
    private val envVarsRepository: EnvVarsRepository,
    private val modRepository: ModRepository,
    private val containerMetricsRepository: ContainerMetricsRepository,
    private val migrationRepository: MigrationRepository
) {

    private val log = LoggerFactory.getLogger(ServerService::class.java)
    private val visibilityResolver = ServerVisibilityResolver(userRepository, groupRepository)
    private val capacityChecker = ResourceCapacityChecker(serverRepository)

    fun isMigrating(id: Uuid): Boolean = migrationRepository.findActiveMigration(id) != null

    fun listServers(userId: Uuid): List<ServerRow> {
        val visibility = visibilityResolver.resolve(userId)
        val rows = when {
            visibility.isGlobal -> serverRepository.listAll()

            visibility.networkIds.isEmpty() && visibility.serverIds.isEmpty() -> return emptyList()

            else -> serverRepository.listByVisibility(
                visibility.networkIds.toList(),
                visibility.serverIds.toList()
            )
        }
        return rows
    }

    fun createServer(
        name: String,
        displayName: String?,
        description: String?,
        nodeId: String,
        networkId: String?,
        serverType: String,
        mcVersion: String,
        itzgImageTag: String,
        memoryMb: Int,
        cpuShares: Int,
    ): ServerRow {
        if (memoryMb <= 0) throw UnprocessableException("memory_mb must be positive")
        if (cpuShares < 0) throw UnprocessableException("cpu_shares must be non-negative")

        val st = runCatching { ServerType.valueOf(serverType) }.getOrNull()
            ?: throw UnprocessableException("Invalid server_type: $serverType")
        val nodeKotlinId = parseUuid(nodeId) ?: throw UnprocessableException("Invalid node_id")
        val networkKotlinId = networkId?.let { parseUuid(it) ?: throw UnprocessableException("Invalid network_id") }

        if (networkKotlinId != null) {
            val existingNodeIds = serverRepository.listByNetworkId(networkKotlinId)
                .map { it.nodeId }
                .distinct()
            val allNodeIds = (existingNodeIds + nodeKotlinId).distinct()
            if (allNodeIds.size > 1) networkService?.validateCrossNodeAssignment(allNodeIds)
        }

        fun attemptCreate(): ServerRow {
            val node = nodeRepository.findById(nodeKotlinId) ?: throw UnprocessableException("Node not found")
            if (node.status != "ACTIVE") throw UnprocessableException("Node is not active")
            if (networkKotlinId != null && networkRepository.findById(networkKotlinId) == null) {
                throw UnprocessableException("Network not found")
            }
            if (serverRepository.findByName(name) != null) throw ConflictException("Server name already taken")

            when (capacityChecker.check(node, excludeServerId = null, memoryMb = memoryMb, cpuShares = cpuShares)) {
                CapacityResult.InsufficientRam -> throw ConflictException("Insufficient RAM capacity on node")
                CapacityResult.InsufficientCpu -> throw ConflictException("Insufficient CPU capacity on node")
                CapacityResult.Ok              -> {}
            }

            val usedPorts = portRepository.findUsedPortsOnNode(nodeKotlinId)
                .toSet()
            val port = PortAllocator.pickFreePort(node.portRangeStart, node.portRangeEnd, usedPorts)
                ?: throw ConflictException("No free ports available on node")

            val stopCommand = if (st.isProxy) "end" else "stop"
            val newServer = transaction {
                val s = serverRepository.create(
                    name = name,
                    displayName = displayName ?: name,
                    description = description,
                    nodeId = nodeKotlinId,
                    networkId = networkKotlinId,
                    serverType = st,
                    mcVersion = mcVersion,
                    itzgImageTag = itzgImageTag,
                    hostPort = port,
                    memoryMb = memoryMb,
                    cpuShares = cpuShares,
                    configMode = "MANAGED",
                    stopCommand = stopCommand
                )

                portRepository.registerPort(nodeKotlinId, port, "TCP", s.id)

                val platformName = settingsRepository.getAll()
                    .firstOrNull { it.key == "CRAFTPANEL_PLATFORM_NAME" }
                    ?.value ?: "CraftPanel"
                val serverTypeDisplay = serverType.lowercase()
                    .replaceFirstChar { it.uppercase() }

                if (!st.isProxy) {
                    val defaults = buildDefaultEnvVars(mcVersion, serverTypeDisplay, platformName)
                    envVarsRepository.replaceEnvVars(
                        s.id,
                        defaults.map { (k, v) ->
                            EnvVarRow(k, v)
                        }
                    )
                }
                else {
                    serverRepository.updateProxySettings(
                        s.id,
                        motd = "$serverTypeDisplay powered by $platformName",
                        maxPlayers = null,
                        forwardingMode = null
                    )
                }

                s
            }

            return newServer
        }

        val result = run {
            var lastEx: java.sql.SQLException? = null
            repeat(3) {
                try {
                    return@run attemptCreate()
                }
                catch (ex: Exception) {
                    val cause = generateSequence(ex as Throwable) { it.cause }
                        .filterIsInstance<java.sql.SQLException>()
                        .firstOrNull()
                    if (cause != null && cause.sqlState?.startsWith("23") == true) {
                        lastEx = cause
                    }
                    else {
                        throw ex
                    }
                }
            }
            throw lastEx ?: RuntimeException("port allocation failed after retries")
        }

        return result
    }

    fun getServer(id: Uuid): ServerRow {
        return serverRepository.findById(id) ?: throw NotFoundException("Server not found")
    }

    fun cloneServer(sourceId: Uuid, name: String, displayName: String?, description: String?): ServerRow {
        val source = serverRepository.findById(sourceId)
            ?: throw NotFoundException("Source server not found")

        val created = createServer(
            name = name,
            displayName = displayName ?: source.displayName,
            description = description ?: source.description,
            nodeId = source.nodeId.toString(),
            networkId = source.networkId?.toString(),
            serverType = source.serverType.toDb(),
            mcVersion = source.mcVersion,
            itzgImageTag = source.itzgImageTag,
            memoryMb = source.memoryMb,
            cpuShares = source.cpuShares
        )

        envVarsRepository.replaceEnvVars(created.id, envVarsRepository.getEnvVars(sourceId))

        modRepository.listMods(sourceId)
            .forEach { mod ->
                modRepository.createMod(
                    serverId = created.id,
                    modrinthProjectId = mod.modrinthProjectId,
                    displayName = mod.displayName,
                    pinStrategy = mod.pinStrategy,
                    pinnedVersionId = mod.pinnedVersionId,
                    installedVersionId = mod.installedVersionId
                )
            }

        return getServer(created.id)
    }

    fun updateServer(
        id: Uuid,
        displayName: String?,
        description: String?,
        networkId: String?,
        mcVersion: String?,
        itzgImageTag: String?
    ) {
        val newNetworkId: Uuid? = networkId?.ifEmpty { null }
            ?.let { parseUuid(it) ?: throw UnprocessableException("Invalid network_id") }

        val serverRow = serverRepository.findById(id) ?: throw NotFoundException("Server not found")

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

        val needsRecreate = mcVersion != null || itzgImageTag != null

        if (networkId != null && newNetworkId == null) {
            // empty string = clear networkId
            serverRepository.clearNetworkId(id)
        }
        serverRepository.updateDetails(
            id,
            displayName,
            description?.ifEmpty { null },
            newNetworkId,
            mcVersion,
            itzgImageTag
        )
        if (needsRecreate) serverRepository.updateNeedsRecreate(id, true)
    }

    fun deleteServer(id: Uuid) {
        val existing = serverRepository.findById(id) ?: throw NotFoundException("Server not found")
        if (existing.status != "STOPPED") throw ConflictException("Server must be STOPPED before deletion")

        val recordId = existing.dnsRecordId
        if (recordId != null) {
            val provider = dnsProvider
                ?: throw ConflictException("Cannot delete server with DNS record: DNS provider not configured")
            val networkId = existing.networkId
            val networkRow = networkId?.let { networkRepository.findById(it) }
            val zoneId = networkRow?.cfZoneId
            val domainSuffix = networkRow?.cfDomainSuffix
            if (zoneId == null || domainSuffix == null) {
                throw ConflictException("Cannot delete server with DNS record: network has no DNS config")
            }
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

        serverRepository.delete(id)
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
            CapacityResult.Ok              -> {}
        }
        serverRepository.updateResources(id, memoryMb, cpuShares, itzgImageTag, true)
    }

    fun getMetrics(id: Uuid, from: Instant, to: Instant): ContainerMetricsSeriesResponse {
        serverRepository.findById(id) ?: throw NotFoundException("Server not found")
        val rows = containerMetricsRepository.getContainerMetricsByRange(id, from, to)
        return ContainerMetricsSeriesResponse(
            serverId = id.toString(),
            series = ContainerMetricsSeries(
                cpuPercent = rows.map { ContainerMetricsPoint(it.recordedAt, it.cpuPercent) },
                ramUsedMb = rows.map { ContainerMetricsPoint(it.recordedAt, it.ramUsedMb.toDouble()) },
                netInBytes = rows.map { ContainerMetricsPointLong(it.recordedAt, it.netInBytes) },
                netOutBytes = rows.map { ContainerMetricsPointLong(it.recordedAt, it.netOutBytes) }
            )
        )
    }
}

internal data class ServerVisibility(val isGlobal: Boolean, val networkIds: Set<Uuid>, val serverIds: Set<Uuid>)

private fun parseUuid(raw: String): Uuid? = runCatching { Uuid.parse(raw) }.getOrNull()

private fun buildDefaultEnvVars(mcVersion: String, serverTypeDisplay: String, platformName: String) = mapOf(
    "MOTD" to "$mcVersion $serverTypeDisplay powered by $platformName",
    "DIFFICULTY" to "easy",
    "MODE" to "survival",
    "HARDCORE" to "false",
    "PVP" to "true",
    "ALLOW_NETHER" to "true",
    "FORCE_GAMEMODE" to "false",
    "SPAWN_ANIMALS" to "true",
    "SPAWN_MONSTERS" to "true",
    "SPAWN_NPCS" to "true",
    "SPAWN_PROTECTION" to "16",
    "ALLOW_FLIGHT" to "false",
    "LEVEL" to "world",
    "LEVEL_TYPE" to "DEFAULT",
    "GENERATE_STRUCTURES" to "true",
    "MAX_WORLD_SIZE" to "29999984",
    "MAX_PLAYERS" to "20",
    "ONLINE_MODE" to "true",
    "ENABLE_WHITELIST" to "false",
    "EXISTING_WHITELIST_FILE" to "SYNCHRONIZE",
    "EXISTING_OPS_FILE" to "SYNCHRONIZE",
    "PLAYER_IDLE_TIMEOUT" to "0",
    "ENFORCE_SECURE_PROFILE" to "true",
    "PREVENT_PROXY_CONNECTIONS" to "false",
    "VIEW_DISTANCE" to "10",
    "SIMULATION_DISTANCE" to "10",
    "MAX_TICK_TIME" to "60000",
    "NETWORK_COMPRESSION_THRESHOLD" to "256",
    "SYNC_CHUNK_WRITES" to "true",
    "ENABLE_COMMAND_BLOCK" to "false",
    "OP_PERMISSION_LEVEL" to "4",
    "FUNCTION_PERMISSION_LEVEL" to "2",
    "BROADCAST_CONSOLE_TO_OPS" to "true",
    "TZ" to "UTC",
    "USE_AIKAR_FLAGS" to "true"
)
