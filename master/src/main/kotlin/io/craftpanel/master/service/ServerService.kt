package io.craftpanel.master.service

import io.craftpanel.master.dns.DnsProvider
import io.craftpanel.master.domain.ConfigMode
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.service.repo.GroupRepository
import io.craftpanel.master.service.repo.NetworkRepository
import io.craftpanel.master.service.repo.NodeRepository
import io.craftpanel.master.service.repo.ServerRepository
import io.craftpanel.master.service.repo.EnvVarRow
import io.craftpanel.master.service.repo.ServerRow
import io.craftpanel.master.service.repo.SettingsRepository
import io.craftpanel.master.service.repo.UserRepository
import io.craftpanel.proto.masterMessage
import io.craftpanel.proto.removeContainerCommand
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class ServerResponse(
    val id: String,
    val name: String,
    @SerialName("display_name") val displayName: String,
    val description: String?,
    @SerialName("server_type") val serverType: String,
    @SerialName("mc_version") val mcVersion: String,
    @SerialName("itzg_image_tag") val itzgImageTag: String,
    val status: ServerStatus,
    @SerialName("node_id") val nodeId: String,
    @SerialName("network_id") val networkId: String?,
    @SerialName("host_port") val hostPort: Int,
    @SerialName("memory_mb") val memoryMb: Int,
    @SerialName("cpu_shares") val cpuShares: Int,
    @SerialName("exposed_externally") val exposedExternally: Boolean,
    @SerialName("public_subdomain") val publicSubdomain: String?,
    @SerialName("custom_hostname") val customHostname: String?,
    @SerialName("canonical_hostname") val canonicalHostname: String?,
    @SerialName("is_migrating") val isMigrating: Boolean,
    @SerialName("needs_recreate") val needsRecreate: Boolean,
    @SerialName("config_mode") val configMode: ConfigMode,
    @SerialName("stop_command") val stopCommand: String,
    @SerialName("last_player_count") val lastPlayerCount: Int?,
    @SerialName("last_player_names") val lastPlayerNames: List<String>?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class CreateServerRequest(
    val name: String,
    @SerialName("display_name") val displayName: String? = null,
    val description: String? = null,
    @SerialName("node_id") val nodeId: String,
    @SerialName("network_id") val networkId: String? = null,
    @SerialName("server_type") val serverType: String,
    @SerialName("mc_version") val mcVersion: String = "LATEST",
    @SerialName("itzg_image_tag") val itzgImageTag: String = "latest",
    @SerialName("memory_mb") val memoryMb: Int,
    @SerialName("cpu_shares") val cpuShares: Int = 0,
)

@Serializable
data class UpdateServerRequest(
    @SerialName("display_name") val displayName: String? = null,
    val description: String? = null,
    @SerialName("network_id") val networkId: String? = null,
    @SerialName("mc_version") val mcVersion: String? = null,
    @SerialName("itzg_image_tag") val itzgImageTag: String? = null,
)

@Serializable
data class PatchResourcesRequest(
    @SerialName("memory_mb") val memoryMb: Int,
    @SerialName("cpu_shares") val cpuShares: Int,
    @SerialName("itzg_image_tag") val itzgImageTag: String? = null,
)

@Serializable
data class PatchExposureRequest(
    @SerialName("exposed_externally") val exposedExternally: Boolean,
    @SerialName("public_subdomain") val publicSubdomain: String? = null,
    @SerialName("custom_hostname") val customHostname: String? = null,
)

@Serializable
data class ContainerMetricsPoint(val t: String, val v: Double)

@Serializable
data class ContainerMetricsPointLong(val t: String, val v: Long)

@Serializable
data class ContainerMetricsSeriesResponse(
    @SerialName("server_id") val serverId: String,
    val series: ContainerMetricsSeries,
)

@Serializable
data class ContainerMetricsSeries(
    @SerialName("cpu_percent") val cpuPercent: List<ContainerMetricsPoint>,
    @SerialName("ram_used_mb") val ramUsedMb: List<ContainerMetricsPoint>,
    @SerialName("net_in_bytes") val netInBytes: List<ContainerMetricsPointLong>,
    @SerialName("net_out_bytes") val netOutBytes: List<ContainerMetricsPointLong>,
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
    private val serverExposure: ServerExposure,
) {

    private val log = LoggerFactory.getLogger(ServerService::class.java)
    private val visibilityResolver = ServerVisibilityResolver(userRepository, groupRepository)
    private val capacityChecker = ResourceCapacityChecker(serverRepository)

    fun listServers(userId: Uuid): List<ServerResponse> {
        val visibility = visibilityResolver.resolve(userId)
        val rows = when {
            visibility.isGlobal                                               -> serverRepository.listAll()
            visibility.networkIds.isEmpty() && visibility.serverIds.isEmpty() -> return emptyList()
            else                                                              -> serverRepository.listByVisibility(
                visibility.networkIds.toList(),
                visibility.serverIds.toList(),
            )
        }
        val ids = rows.map { it.id }
        val migratingIds = if (ids.isEmpty()) emptySet()
        else rows.filter { serverRepository.findActiveMigration(it.id) != null }
            .map { it.id }
            .toSet()
        return rows.map { row -> row.toResponse(serverExposure, row.id in migratingIds) }
    }

    fun createServer(req: CreateServerRequest): ServerResponse {
        if (req.memoryMb <= 0) throw UnprocessableException("memory_mb must be positive")
        if (req.cpuShares < 0) throw UnprocessableException("cpu_shares must be non-negative")

        val nodeKotlinId = parseUuid(req.nodeId) ?: throw UnprocessableException("Invalid node_id")
        val networkKotlinId = req.networkId?.let { parseUuid(it) ?: throw UnprocessableException("Invalid network_id") }

        if (networkKotlinId != null) {
            val existingNodeIds = serverRepository.listByNetworkId(networkKotlinId)
                .map { it.nodeId }
                .distinct()
            val allNodeIds = (existingNodeIds + nodeKotlinId).distinct()
            if (allNodeIds.size > 1) networkService?.validateCrossNodeAssignment(allNodeIds)
        }

        data class CreateResult(val status: String, val server: ServerResponse? = null)

        fun attemptCreate(): CreateResult {
            val node = nodeRepository.findById(nodeKotlinId) ?: return CreateResult("node_not_found")
            if (node.status != "ACTIVE") return CreateResult("node_not_active")
            if (networkKotlinId != null && networkRepository.findById(networkKotlinId) == null)
                return CreateResult("network_not_found")
            if (serverRepository.findByName(req.name) != null) return CreateResult("name_taken")

            when (capacityChecker.check(node, excludeServerId = null, memoryMb = req.memoryMb, cpuShares = req.cpuShares)) {
                CapacityResult.InsufficientRam -> return CreateResult("insufficient_ram")
                CapacityResult.InsufficientCpu -> return CreateResult("insufficient_cpu")
                CapacityResult.Ok              -> {}
            }

            val usedPorts = serverRepository.findUsedPortsOnNode(nodeKotlinId)
                .toSet()
            val port = PortAllocator.pickFreePort(node.portRangeStart, node.portRangeEnd, usedPorts)
                ?: return CreateResult("no_ports")

            val stopCommand = if (req.serverType in PROXY_SERVER_TYPES) "end" else "stop"
            val newServer = serverRepository.create(
                name = req.name,
                displayName = req.displayName ?: req.name,
                description = req.description,
                nodeId = nodeKotlinId,
                networkId = networkKotlinId,
                serverType = req.serverType,
                mcVersion = req.mcVersion,
                itzgImageTag = req.itzgImageTag,
                hostPort = port,
                memoryMb = req.memoryMb,
                cpuShares = req.cpuShares,
                configMode = "MANAGED",
                stopCommand = stopCommand,
            )

            serverRepository.registerPort(nodeKotlinId, port, "TCP", newServer.id)

            if (req.serverType !in PROXY_SERVER_TYPES) {
                val platformName = settingsRepository.getAll()
                    .firstOrNull { it.key == "CRAFTPANEL_PLATFORM_NAME" }
                    ?.value ?: "CraftPanel"
                val serverTypeDisplay = req.serverType.lowercase()
                    .replaceFirstChar { it.uppercase() }
                val defaults = buildDefaultEnvVars(req.mcVersion, serverTypeDisplay, platformName)
                serverRepository.replaceEnvVars(newServer.id, defaults.map { (k, v) ->
                    EnvVarRow(k, v)
                })
            }

            return CreateResult("ok", newServer.toResponse(serverExposure, false))
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
                    else throw ex
                }
            }
            throw lastEx ?: RuntimeException("port allocation failed after retries")
        }

        return when (result.status) {
            "ok"                -> result.server!!
            "node_not_found"    -> throw UnprocessableException("Node not found")
            "node_not_active"   -> throw UnprocessableException("Node is not active")
            "network_not_found" -> throw UnprocessableException("Network not found")
            "name_taken"        -> throw ConflictException("Server name already taken")
            "insufficient_ram"  -> throw ConflictException("Insufficient RAM capacity on node")
            "insufficient_cpu"  -> throw ConflictException("Insufficient CPU capacity on node")
            else                -> throw ConflictException("No free ports available on node")
        }
    }

    fun getServer(id: Uuid): ServerResponse {
        val row = serverRepository.findById(id) ?: throw NotFoundException("Server not found")
        val isMigrating = serverRepository.findActiveMigration(id) != null
        return row.toResponse(serverExposure, isMigrating)
    }

    fun updateServer(id: Uuid, req: UpdateServerRequest) {
        val newNetworkId: Uuid? = req.networkId?.ifEmpty { null }
            ?.let { parseUuid(it) ?: throw UnprocessableException("Invalid network_id") }

        val serverRow = serverRepository.findById(id) ?: throw NotFoundException("Server not found")

        if (newNetworkId != null) {
            val existingNodeIds = serverRepository.listByNetworkId(newNetworkId)
                .filter { it.id != id }
                .map { it.nodeId }
                .distinct()
            val allNodeIds = (existingNodeIds + serverRow.nodeId).distinct()
            if (allNodeIds.size > 1) networkService?.validateCrossNodeAssignment(allNodeIds)
            if (networkRepository.findById(newNetworkId) == null)
                throw UnprocessableException("Network not found")
        }

        val needsRecreate = req.mcVersion != null || req.itzgImageTag != null

        if (req.networkId != null && newNetworkId == null) {
            // empty string = clear networkId
            serverRepository.clearNetworkId(id)
        }
        serverRepository.updateDetails(
            id,
            req.displayName,
            req.description?.ifEmpty { null },
            newNetworkId,
            req.mcVersion,
            req.itzgImageTag,
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
            if (zoneId == null || domainSuffix == null)
                throw ConflictException("Cannot delete server with DNS record: network has no DNS config")
            runCatching { provider.deleteARecord(zoneId, recordId) }
                .onFailure { log.warn("Failed to delete DNS record $recordId during server delete", it) }
        }

        val nodeId = existing.nodeId.toString()
        gateway.sendToNode(nodeId, masterMessage {
            removeContainer = removeContainerCommand {
                serverId = id.toString()
                containerName = "$containerNamePrefix-$id"
                force = true
            }
        })

        serverRepository.delete(id)
    }

    fun updateResources(id: Uuid, req: PatchResourcesRequest) {
        if (req.memoryMb <= 0) throw UnprocessableException("memory_mb must be positive")
        if (req.cpuShares < 0) throw UnprocessableException("cpu_shares must be non-negative")
        val existing = serverRepository.findById(id) ?: throw NotFoundException("Server not found")
        val nodeKotlinId = existing.nodeId
        val node = nodeRepository.findById(nodeKotlinId) ?: throw UnprocessableException("Node not found")
        when (capacityChecker.check(node, excludeServerId = id, memoryMb = req.memoryMb, cpuShares = req.cpuShares)) {
            CapacityResult.InsufficientRam -> throw ConflictException("Insufficient RAM capacity on node")
            CapacityResult.InsufficientCpu -> throw ConflictException("Insufficient CPU capacity on node")
            CapacityResult.Ok              -> {}
        }
        serverRepository.updateResources(id, req.memoryMb, req.cpuShares, req.itzgImageTag, true)
    }

    fun getMetrics(id: Uuid, from: Instant, to: Instant): ContainerMetricsSeriesResponse {
        serverRepository.findById(id) ?: throw NotFoundException("Server not found")
        val rows = serverRepository.getContainerMetricsByRange(id, from, to)
        return ContainerMetricsSeriesResponse(
            serverId = id.toString(),
            series = ContainerMetricsSeries(
                cpuPercent = rows.map { ContainerMetricsPoint(it.recordedAt, it.cpuPercent) },
                ramUsedMb = rows.map { ContainerMetricsPoint(it.recordedAt, it.ramUsedMb.toDouble()) },
                netInBytes = rows.map { ContainerMetricsPointLong(it.recordedAt, it.netInBytes) },
                netOutBytes = rows.map { ContainerMetricsPointLong(it.recordedAt, it.netOutBytes) },
            ),
        )
    }
}

internal data class ServerVisibility(
    val isGlobal: Boolean,
    val networkIds: Set<Uuid>,
    val serverIds: Set<Uuid>,
)

internal val PROXY_SERVER_TYPES = setOf("VELOCITY", "BUNGEECORD", "WATERFALL")

internal fun ServerRow.toResponse(serverExposure: ServerExposure, isMigrating: Boolean): ServerResponse {
    val canonicalHostname = serverExposure.canonicalHostname(this)
    return ServerResponse(
        id = id.toString(),
        name = name,
        displayName = displayName,
        description = description,
        serverType = serverType,
        mcVersion = mcVersion,
        itzgImageTag = itzgImageTag,
        status = ServerStatus.fromDb(status),
        nodeId = nodeId.toString(),
        networkId = networkId?.toString(),
        hostPort = hostPort,
        memoryMb = memoryMb,
        cpuShares = cpuShares,
        exposedExternally = exposedExternally,
        publicSubdomain = publicSubdomain,
        customHostname = customHostname,
        canonicalHostname = canonicalHostname,
        isMigrating = isMigrating,
        needsRecreate = needsRecreate,
        configMode = ConfigMode.fromDb(configMode),
        stopCommand = stopCommand,
        lastPlayerCount = lastPlayerCount,
        lastPlayerNames = lastPlayerNames?.split(",")
            ?.filter { it.isNotBlank() },
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

private fun parseUuid(raw: String): Uuid? = runCatching { Uuid.parse(raw) }.getOrNull()

private fun buildDefaultEnvVars(mcVersion: String, serverTypeDisplay: String, platformName: String) = mapOf(
    "MOTD" to "${mcVersion} $serverTypeDisplay powered by $platformName",
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
    "USE_AIKAR_FLAGS" to "true",
)
