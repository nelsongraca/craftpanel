package io.craftpanel.master.service

import io.craftpanel.proto.*
import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.ScopeType
import io.craftpanel.master.database.schema.*
import io.craftpanel.master.config.ImagesConfig
import io.craftpanel.master.domain.ConfigMode
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.dns.DnsProvider
import org.jetbrains.exposed.v1.core.ResultRow
import org.slf4j.LoggerFactory
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid
import kotlin.time.Clock
import io.craftpanel.master.util.toUtcString

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
    private val modService: ModService,
    private val networkService: NetworkService? = null,
    private val dnsProvider: DnsProvider? = null,
    private val images: ImagesConfig = ImagesConfig("itzg/minecraft-server", "itzg/mc-proxy"),
    private val containerNamePrefix: String = "craftpanel",
    private val lifecycle: ContainerLifecycle = ContainerLifecycle(
        gateway = gateway,
        modService = modService,
        images = images,
        containerNamePrefix = containerNamePrefix,
    ),
) {

    private val log = LoggerFactory.getLogger(ServerService::class.java)


    fun listServers(userId: Uuid): List<ServerResponse> {
        val visibility = resolveServerVisibility(userId)
        return transaction {
            val netIds = visibility.networkIds.toList()
            val srvIds = visibility.serverIds.toList()
            val rows = when {
                visibility.isGlobal                  -> Servers.selectAll()
                    .toList()

                netIds.isEmpty() && srvIds.isEmpty() -> return@transaction emptyList()
                else                                 -> Servers.selectAll()
                    .where {
                        val conds = buildList<Op<Boolean>> {
                            if (netIds.isNotEmpty()) add(Servers.networkId inList netIds)
                            if (srvIds.isNotEmpty()) add(Servers.id inList srvIds)
                        }
                        conds.reduce { a, b -> a or b }
                    }
                    .toList()
            }
            val ids = rows.map { it[Servers.id] }
            val migratingIds = if (ids.isEmpty()) emptySet()
            else {
                ServerMigrations.selectAll()
                    .where {
                        (ServerMigrations.serverId inList ids) and
                                (ServerMigrations.status inList listOf("PENDING", "RUNNING"))
                    }
                    .map { it[ServerMigrations.serverId] }
                    .toSet()
            }
            rows.map { row -> rowToServerResponse(row, row[Servers.id] in migratingIds) }
        }
    }

    fun createServer(req: CreateServerRequest): ServerResponse {
        if (req.memoryMb <= 0) throw UnprocessableException("memory_mb must be positive")
        if (req.cpuShares < 0) throw UnprocessableException("cpu_shares must be non-negative")

        val nodeKotlinId = parseUuid(req.nodeId) ?: throw UnprocessableException("Invalid node_id")
        val networkKotlinId = req.networkId?.let { parseUuid(it) ?: throw UnprocessableException("Invalid network_id") }

        if (networkKotlinId != null) {
            val existingNodeIds = transaction {
                Servers.selectAll()
                    .where { Servers.networkId eq networkKotlinId }
                    .map { it[Servers.nodeId] }
                    .distinct()
            }
            val allNodeIds = (existingNodeIds + nodeKotlinId).distinct()
            if (allNodeIds.size > 1) {
                networkService?.validateCrossNodeAssignment(allNodeIds)
            }
        }

        data class CreateResult(val status: String, val server: ServerResponse? = null)

        val result = transaction {
            val node = Nodes.selectAll()
                .where { Nodes.id eq nodeKotlinId }
                .firstOrNull()
                ?: return@transaction CreateResult("node_not_found")
            if (node[Nodes.status] != "ACTIVE") return@transaction CreateResult("node_not_active")
            if (networkKotlinId != null) {
                val netExists = ServerNetworks.selectAll()
                    .where { ServerNetworks.id eq networkKotlinId }
                    .firstOrNull() != null
                if (!netExists) return@transaction CreateResult("network_not_found")
            }
            val nameTaken = Servers.selectAll()
                .where { Servers.name eq req.name }
                .firstOrNull() != null
            if (nameTaken) return@transaction CreateResult("name_taken")

            val totalRam = node[Nodes.totalRamMb]
            val totalCpu = node[Nodes.totalCpuShares]
            val existing = Servers.selectAll()
                .where { Servers.nodeId eq nodeKotlinId }
                .toList()
            val usedRam = existing.sumOf { it[Servers.memoryMb] }
            val usedCpu = existing.sumOf { it[Servers.cpuShares] }
            val systemRamUsed = node[Nodes.systemRamUsedMb] ?: 0
            val effectiveUsedRam = maxOf(usedRam, systemRamUsed)
            if (effectiveUsedRam + req.memoryMb > totalRam) return@transaction CreateResult("insufficient_ram")
            if (totalCpu > 0 && usedCpu + req.cpuShares > totalCpu) return@transaction CreateResult("insufficient_cpu")

            val usedPorts = PortRegistry.selectAll()
                .where { (PortRegistry.nodeId eq nodeKotlinId) and (PortRegistry.protocol eq "TCP") }
                .map { it[PortRegistry.port] }
                .toSet()
            val port = (node[Nodes.portRangeStart]..node[Nodes.portRangeEnd]).firstOrNull { it !in usedPorts }
                ?: return@transaction CreateResult("no_ports")

            val stopCommand = if (req.serverType in PROXY_SERVER_TYPES) "end" else "stop"
            val insertedId = Servers.insert {
                it[name] = req.name
                it[displayName] = req.displayName ?: req.name
                it[description] = req.description
                it[nodeId] = nodeKotlinId
                it[networkId] = networkKotlinId
                it[serverType] = req.serverType
                it[mcVersion] = req.mcVersion
                it[itzgImageTag] = req.itzgImageTag
                it[Servers.stopCommand] = stopCommand
                it[hostPort] = port
                it[memoryMb] = req.memoryMb
                it[cpuShares] = req.cpuShares
            }[Servers.id]
            PortRegistry.insert {
                it[PortRegistry.nodeId] = nodeKotlinId
                it[PortRegistry.port] = port
                it[PortRegistry.protocol] = "TCP"
                it[PortRegistry.serverId] = insertedId
            }
            if (req.serverType !in PROXY_SERVER_TYPES) {
                val platformName = SystemSettings.selectAll()
                    .where { SystemSettings.key eq "CRAFTPANEL_PLATFORM_NAME" }
                    .firstOrNull()
                    ?.get(SystemSettings.value) ?: "CraftPanel"
                val serverTypeDisplay = req.serverType.lowercase()
                    .replaceFirstChar { it.uppercase() }
                val defaults = mapOf(
                    "MOTD" to "${req.mcVersion} $serverTypeDisplay powered by $platformName",
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
                for ((k, v) in defaults) {
                    ServerEnvVars.insert {
                        it[ServerEnvVars.serverId] = insertedId
                        it[ServerEnvVars.key] = k
                        it[ServerEnvVars.value] = v
                    }
                }
            }
            val row = Servers.selectAll()
                .where { Servers.id eq insertedId }
                .first()
            CreateResult("ok", rowToServerResponse(row, false))
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

    fun getServer(id: kotlin.uuid.Uuid): ServerResponse {
        val (row, isMigrating) = transaction {
            val r = Servers.selectAll()
                .where { Servers.id eq id }
                .firstOrNull() ?: return@transaction null
            val migrating = ServerMigrations.selectAll()
                .where { (ServerMigrations.serverId eq id) and (ServerMigrations.status inList listOf("PENDING", "RUNNING")) }
                .firstOrNull() != null
            r to migrating
        } ?: throw NotFoundException("Server not found")
        return rowToServerResponse(row, isMigrating)
    }

    fun updateServer(id: kotlin.uuid.Uuid, req: UpdateServerRequest) {
        val newNetworkId: kotlin.uuid.Uuid? = req.networkId?.ifEmpty { null }
            ?.let { parseUuid(it) ?: throw UnprocessableException("Invalid network_id") }

        if (newNetworkId != null) {
            val (serverNodeId, existingNodeIds) = transaction {
                val serverRow = Servers.selectAll()
                    .where { Servers.id eq id }
                    .firstOrNull()
                val nodeId = serverRow?.get(Servers.nodeId)
                val networkNodeIds = Servers.selectAll()
                    .where { (Servers.networkId eq newNetworkId) and (Servers.id neq id) }
                    .map { it[Servers.nodeId] }
                    .distinct()
                nodeId to networkNodeIds
            }
            if (serverNodeId != null) {
                val allNodeIds = (existingNodeIds + serverNodeId).distinct()
                if (allNodeIds.size > 1) {
                    networkService?.validateCrossNodeAssignment(allNodeIds)
                }
            }
        }

        val result = transaction {
            Servers.selectAll()
                .where { Servers.id eq id }
                .firstOrNull()
                ?: return@transaction "not_found"
            if (newNetworkId != null) {
                val netExists = ServerNetworks.selectAll()
                    .where { ServerNetworks.id eq newNetworkId }
                    .firstOrNull() != null
                if (!netExists) return@transaction "network_not_found"
            }
            Servers.update({ Servers.id eq id }) {
                if (req.displayName != null) it[displayName] = req.displayName
                if (req.description != null) it[description] = req.description.ifEmpty { null }
                if (req.networkId != null) it[networkId] = newNetworkId
                if (req.mcVersion != null) {
                    it[mcVersion] = req.mcVersion
                    it[needsRecreate] = true
                }
                if (req.itzgImageTag != null) {
                    it[itzgImageTag] = req.itzgImageTag
                    it[needsRecreate] = true
                }
                it[updatedAt] = Clock.System.now()
                    .toLocalDateTime(TimeZone.UTC)
            }
            "ok"
        }
        when (result) {
            "not_found"         -> throw NotFoundException("Server not found")
            "network_not_found" -> throw UnprocessableException("Network not found")
        }
    }

    fun deleteServer(id: kotlin.uuid.Uuid) {
        val existing = transaction {
            Servers.selectAll()
                .where { Servers.id eq id }
                .firstOrNull()
        }
            ?: throw NotFoundException("Server not found")
        if (existing[Servers.status] != "STOPPED") throw ConflictException("Server must be STOPPED before deletion")

        val recordId = existing[Servers.dnsRecordId]
        if (recordId != null) {
            val provider = dnsProvider
                ?: throw ConflictException("Cannot delete server with DNS record: DNS provider not configured")
            val dns = resolveNetworkDns(existing[Servers.networkId])
                ?: throw ConflictException("Cannot delete server with DNS record: network has no DNS config")
            runCatching { provider.deleteARecord(dns.zoneId, recordId) }
                .onFailure { log.warn("Failed to delete DNS record $recordId during server delete", it) }
        }

        val nodeId = existing[Servers.nodeId].toString()
        gateway.sendToNode(
            nodeId, masterMessage {
                removeContainer = removeContainerCommand {
                    serverId = id.toString()
                    containerName = "$containerNamePrefix-$id"
                    force = true
                }
            }
        )

        transaction {
            val migrationIds = ServerMigrations.selectAll()
                .where { ServerMigrations.serverId eq id }
                .map { it[ServerMigrations.id] }
            if (migrationIds.isNotEmpty()) {
                MigrationStepLog.deleteWhere { MigrationStepLog.migrationId inList migrationIds }
                ServerMigrations.deleteWhere { ServerMigrations.serverId eq id }
            }
            PortRegistry.deleteWhere { PortRegistry.serverId eq id }
            Backups.deleteWhere { Backups.serverId eq id }
            Servers.deleteWhere { Servers.id eq id }
        }
    }

    fun updateResources(id: kotlin.uuid.Uuid, req: PatchResourcesRequest) {
        if (req.memoryMb <= 0) throw UnprocessableException("memory_mb must be positive")
        if (req.cpuShares < 0) throw UnprocessableException("cpu_shares must be non-negative")
        val existing = transaction {
            Servers.selectAll()
                .where { Servers.id eq id }
                .firstOrNull()
        }
            ?: throw NotFoundException("Server not found")
        val nodeKotlinId = existing[Servers.nodeId]
        val result = transaction {
            val node = Nodes.selectAll()
                .where { Nodes.id eq nodeKotlinId }
                .firstOrNull()
                ?: return@transaction "node_not_found"
            val others = Servers.selectAll()
                .where { (Servers.nodeId eq nodeKotlinId) and (Servers.id neq id) }
                .toList()
            val usedRam = others.sumOf { it[Servers.memoryMb] }
            val usedCpu = others.sumOf { it[Servers.cpuShares] }
            // Use allocated RAM from other servers as the baseline; don't apply system RAM floor
            // for updates since systemRamUsedMb includes the running container being resized.
            if (usedRam + req.memoryMb > node[Nodes.totalRamMb]) return@transaction "insufficient_ram"
            if (node[Nodes.totalCpuShares] > 0 && usedCpu + req.cpuShares > node[Nodes.totalCpuShares]) return@transaction "insufficient_cpu"
            Servers.update({ Servers.id eq id }) {
                it[memoryMb] = req.memoryMb
                it[cpuShares] = req.cpuShares
                if (req.itzgImageTag != null) it[itzgImageTag] = req.itzgImageTag
                it[needsRecreate] = true
                it[updatedAt] = Clock.System.now()
                    .toLocalDateTime(TimeZone.UTC)
            }
            "ok"
        }
        when (result) {
            "node_not_found"   -> throw UnprocessableException("Node not found")
            "insufficient_ram" -> throw ConflictException("Insufficient RAM capacity on node")
            "insufficient_cpu" -> throw ConflictException("Insufficient CPU capacity on node")
        }
    }

    fun startServer(id: kotlin.uuid.Uuid) {
        val serverRow = transaction {
            Servers.selectAll()
                .where { Servers.id eq id }
                .firstOrNull()
        }
            ?: throw NotFoundException("Server not found")
        val status = ServerStatus.fromDb(serverRow[Servers.status])
        if (status == ServerStatus.HEALTHY || status == ServerStatus.STARTING)
            throw ConflictException("Server is already running")
        val publicHostname = buildMcRouterLabel(serverRow)
        // Write STARTING before dispatching to the agent: the background ServerStatusEvent
        // consumer may write HEALTHY as soon as the agent reports, and a late STARTING write
        // would clobber it, leaving the server stuck STARTING.
        transaction {
            Servers.update({ Servers.id eq id }) {
                it[Servers.status] = "STARTING"
                it[Servers.updatedAt] = Clock.System.now()
                    .toLocalDateTime(TimeZone.UTC)
            }
        }
        lifecycle.sendStart(serverRow, needsRecreate = serverRow[Servers.needsRecreate], publicHostname = publicHostname)
    }

    fun stopServer(id: kotlin.uuid.Uuid) {
        val serverRow = transaction {
            Servers.selectAll()
                .where { Servers.id eq id }
                .firstOrNull()
        }
            ?: throw NotFoundException("Server not found")
        if (serverRow[Servers.status] == "STOPPED") throw ConflictException("Server is already stopped")
        val nodeId = serverRow[Servers.nodeId].toString()
        // Write STOPPING before dispatching: the background consumer may write STOPPED as soon
        // as the agent reports, and a late STOPPING write would clobber it.
        transaction {
            Servers.update({ Servers.id eq id }) {
                it[status] = "STOPPING"; it[updatedAt] = Clock.System.now()
                .toLocalDateTime(TimeZone.UTC)
            }
        }
        lifecycle.sendStop(serverRow, nodeId)
    }

    fun restartServer(id: kotlin.uuid.Uuid) {
        val serverRow = transaction {
            Servers.selectAll()
                .where { Servers.id eq id }
                .firstOrNull()
        }
            ?: throw NotFoundException("Server not found")
        if (ServerStatus.fromDb(serverRow[Servers.status]).isStopped) throw ConflictException("Server is not running")
        val nodeId = serverRow[Servers.nodeId].toString()
        // Write STARTING before dispatching so the restart is reflected in the DB and a late write
        // cannot clobber a HEALTHY/STOPPED status reported by the background consumer.
        transaction {
            Servers.update({ Servers.id eq id }) {
                it[Servers.status] = "STARTING"
                it[Servers.updatedAt] = Clock.System.now()
                    .toLocalDateTime(TimeZone.UTC)
            }
        }
        if (serverRow[Servers.needsRecreate]) {
            lifecycle.sendStart(serverRow, needsRecreate = true, publicHostname = buildMcRouterLabel(serverRow))
        }
        else {
            lifecycle.sendStop(serverRow, nodeId)
            lifecycle.sendStart(serverRow, needsRecreate = false, publicHostname = buildMcRouterLabel(serverRow))
        }
    }

    fun getMetrics(id: kotlin.uuid.Uuid, from: Instant, to: Instant): ContainerMetricsSeriesResponse {
        val exists = transaction {
            Servers.selectAll()
                .where { Servers.id eq id }
                .firstOrNull() != null
        }
        if (!exists) throw NotFoundException("Server not found")
        val fromLdt = from.toLocalDateTime(TimeZone.UTC)
        val toLdt = to.toLocalDateTime(TimeZone.UTC)
        val rows = transaction {
            ContainerMetrics.selectAll()
                .where {
                    (ContainerMetrics.serverId eq id) and
                            (ContainerMetrics.recordedAt greaterEq fromLdt) and
                            (ContainerMetrics.recordedAt lessEq toLdt)
                }
                .orderBy(ContainerMetrics.recordedAt, SortOrder.ASC)
                .toList()
        }
        return ContainerMetricsSeriesResponse(
            serverId = id.toString(),
            series = ContainerMetricsSeries(
                cpuPercent = rows.map { ContainerMetricsPoint(it[ContainerMetrics.recordedAt].toUtcString(), it[ContainerMetrics.cpuPercent]) },
                ramUsedMb = rows.map { ContainerMetricsPoint(it[ContainerMetrics.recordedAt].toUtcString(), it[ContainerMetrics.ramUsedMb].toDouble()) },
                netInBytes = rows.map { ContainerMetricsPointLong(it[ContainerMetrics.recordedAt].toUtcString(), it[ContainerMetrics.netInBytes]) },
                netOutBytes = rows.map { ContainerMetricsPointLong(it[ContainerMetrics.recordedAt].toUtcString(), it[ContainerMetrics.netOutBytes]) },
            )
        )
    }

    fun updateExposure(id: kotlin.uuid.Uuid, req: PatchExposureRequest) {
        val serverRow = transaction {
            Servers.selectAll()
                .where { Servers.id eq id }
                .firstOrNull()
        } ?: throw NotFoundException("Server not found")

        if (req.exposedExternally && req.publicSubdomain != null) {
            val taken = transaction {
                Servers.selectAll()
                    .where { (Servers.publicSubdomain eq req.publicSubdomain) and (Servers.id neq id) }
                    .firstOrNull() != null
            }
            if (taken) throw UnprocessableException("Public subdomain already taken")
        }

        // Validate and check custom_hostname if provided
        val resolvedCustomHostname: String? = if (req.customHostname != null) {
            val ch = req.customHostname.trim()
            if (ch.isEmpty()) {
                // empty string means clear the custom hostname
                null
            }
            else {
                validateCustomHostname(ch, id)
                ch
            }
        }
        else {
            // null means "don't change" — but we still carry through the existing value in the DB
            serverRow[Servers.customHostname]
        }

        val existingRecordId = serverRow[Servers.dnsRecordId]
        var newHostname: String? = null
        var newRecordId: String? = null

        if (req.exposedExternally && req.publicSubdomain != null) {
            val provider = dnsProvider
            val dns = resolveNetworkDns(serverRow[Servers.networkId])

            if (provider != null && dns == null) {
                throw UnprocessableException(
                    "Server's network has no DNS zone configured (set dns_zone_id and dns_domain_suffix on the network)"
                )
            }

            val fullHostname = if (dns != null) {
                "${req.publicSubdomain}.${dns.domainSuffix}"
            }
            else {
                resolvePublicHostname(req.publicSubdomain, serverRow[Servers.networkId])
            }

            newRecordId = if (provider != null && dns != null) {
                val nodeIp = transaction {
                    Nodes.selectAll()
                        .where { Nodes.id eq serverRow[Servers.nodeId] }
                        .first()
                }[Nodes.publicIp]
                runCatching {
                    if (existingRecordId != null) {
                        provider.updateARecord(dns.zoneId, existingRecordId, nodeIp)
                        existingRecordId
                    }
                    else {
                        provider.createARecord(dns.zoneId, fullHostname ?: req.publicSubdomain, nodeIp)
                    }
                }.getOrElse { ex -> throw BadGatewayException("DNS provider error: ${ex.message}") }
            }
            else null

            newHostname = fullHostname
        }

        if (!req.exposedExternally) {
            val provider = dnsProvider
            if (existingRecordId != null && provider != null) {
                val dns = resolveNetworkDns(serverRow[Servers.networkId])
                if (dns != null) {
                    runCatching { provider.deleteARecord(dns.zoneId, existingRecordId) }
                        .onFailure { log.warn("Failed to delete DNS record $existingRecordId — continuing", it) }
                }
            }
        }

        // Determine if custom_hostname changed — triggers recreate
        val prevCustomHostname = serverRow[Servers.customHostname]
        val customHostnameChanged = resolvedCustomHostname != prevCustomHostname

        transaction {
            Servers.update({ Servers.id eq id }) {
                it[exposedExternally] = req.exposedExternally
                if (req.publicSubdomain != null) {
                    it[publicSubdomain] = req.publicSubdomain
                    if (req.exposedExternally) {
                        it[dnsRecordName] = newHostname
                        it[dnsRecordId] = newRecordId
                    }
                }
                if (!req.exposedExternally) {
                    it[publicSubdomain] = null
                    it[dnsRecordName] = null
                    it[dnsRecordId] = null
                }
                it[customHostname] = resolvedCustomHostname
                it[updatedAt] = Clock.System.now()
                    .toLocalDateTime(TimeZone.UTC)
            }
        }

        val currentStatus = ServerStatus.fromDb(serverRow[Servers.status])
        if (currentStatus.isRunning) {
            val freshRow = transaction {
                Servers.selectAll()
                    .where { Servers.id eq id }
                    .first()
            }
            // Recreate if exposure flags changed or custom_hostname changed so mc-router label is updated.
            val needsRecreate = req.publicSubdomain != null || customHostnameChanged
            if (needsRecreate) {
                // Recreate transitions the container through a restart; mark STARTING before dispatch
                // so the DB reflects it and a late write cannot clobber the consumer's HEALTHY.
                transaction {
                    Servers.update({ Servers.id eq id }) {
                        it[Servers.status] = "STARTING"
                        it[Servers.updatedAt] = Clock.System.now()
                            .toLocalDateTime(TimeZone.UTC)
                    }
                }
                lifecycle.sendStart(freshRow, needsRecreate = true, publicHostname = buildMcRouterLabel(freshRow))
            }
        }
    }

    /**
     * Validates a custom hostname against RFC-1123 rules and panel-specific constraints:
     * - Must be a valid RFC-1123 hostname (labels separated by dots, each label [a-z0-9-])
     * - Must not collide with any server's existing custom_hostname
     * - Must not collide with any server's managed dns_record_name
     * - Must not end with any panel-managed domain suffix (network cfDomainSuffix values or global dns_domain_suffix)
     */
    private fun validateCustomHostname(hostname: String, excludeServerId: kotlin.uuid.Uuid) {
        val rfc1123Label = Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$")
        val labels = hostname.split(".")
        if (labels.isEmpty() || labels.any { !it.matches(rfc1123Label) }) {
            throw UnprocessableException("custom_hostname must be a valid RFC-1123 hostname (e.g. play.yourdomain.com)")
        }

        // Check collision with existing custom hostnames
        val customTaken = transaction {
            Servers.selectAll()
                .where { (Servers.customHostname eq hostname) and (Servers.id neq excludeServerId) }
                .firstOrNull() != null
        }
        if (customTaken) throw UnprocessableException("custom_hostname is already in use by another server")

        // Check collision with managed dns_record_name values
        val managedTaken = transaction {
            Servers.selectAll()
                .where { (Servers.dnsRecordName eq hostname) and (Servers.id neq excludeServerId) }
                .firstOrNull() != null
        }
        if (managedTaken) throw UnprocessableException("custom_hostname conflicts with a managed DNS record name")

        // Reject hostnames under panel-managed suffixes
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

    /**
     * Collects all panel-managed domain suffixes: per-network cfDomainSuffix values UNION
     * the global dns_domain_suffix system setting.
     */
    private fun collectManagedSuffixes(): Set<String> = transaction {
        val suffixes = mutableSetOf<String>()
        ServerNetworks.selectAll()
            .mapNotNull { it[ServerNetworks.cfDomainSuffix] }
            .forEach { suffixes += it }
        SystemSettings.selectAll()
            .where { SystemSettings.key eq "dns_domain_suffix" }
            .firstOrNull()
            ?.get(SystemSettings.value)
            ?.let { suffixes += it }
        suffixes
    }

    /**
     * Builds the mc-router.host label value: comma-joined list of [managedHostname, customHostname],
     * with nulls filtered. When exposed_externally is true and public_subdomain is set, the managed
     * hostname is included. The custom hostname is always included when set (even when expose is off).
     *
     * Returns null if no hostnames are available.
     */
    private fun buildMcRouterLabel(row: ResultRow): String? {
        val managed = if (row[Servers.exposedExternally] && row[Servers.publicSubdomain] != null) {
            row[Servers.dnsRecordName]
                ?: resolvePublicHostname(row[Servers.publicSubdomain]!!, row[Servers.networkId])
        }
        else null
        val custom = row[Servers.customHostname]
        val parts = listOfNotNull(managed, custom)
        return if (parts.isEmpty()) null else parts.joinToString(",")
    }

    private data class NetworkDns(val zoneId: String, val domainSuffix: String)

    private fun resolveNetworkDns(networkId: kotlin.uuid.Uuid?): NetworkDns? = transaction {
        networkId ?: return@transaction null
        ServerNetworks.selectAll()
            .where { ServerNetworks.id eq networkId }
            .firstOrNull()
            ?.let { row ->
                val zoneId = row[ServerNetworks.cfZoneId] ?: return@let null
                val suffix = row[ServerNetworks.cfDomainSuffix] ?: return@let null
                NetworkDns(zoneId, suffix)
            }
    }

    private fun resolvePublicHostname(subdomain: String, networkId: kotlin.uuid.Uuid?): String? {
        val suffix = transaction {
            if (networkId != null) {
                ServerNetworks.selectAll()
                    .where { ServerNetworks.id eq networkId }
                    .firstOrNull()
                    ?.get(ServerNetworks.cfDomainSuffix)
            }
            else null
        } ?: transaction {
            SystemSettings.selectAll()
                .where { SystemSettings.key eq "dns_domain_suffix" }
                .firstOrNull()
                ?.get(SystemSettings.value)
        }
        return suffix?.let { "$subdomain.$it" }
    }
}

internal data class ServerVisibility(
    val isGlobal: Boolean,
    val networkIds: Set<kotlin.uuid.Uuid>,
    val serverIds: Set<kotlin.uuid.Uuid>,
)

internal fun resolveServerVisibility(userId: Uuid): ServerVisibility = transaction {
    val user = Users.selectAll()
        .where { Users.id eq userId }
        .firstOrNull()
    if (user == null || !user[Users.isActive]) return@transaction ServerVisibility(false, emptySet(), emptySet())
    val assignments = UserGroupAssignments.selectAll()
        .where { UserGroupAssignments.userId eq userId }
        .toList()
    val groupIds = assignments.map { it[UserGroupAssignments.groupId] }
        .toSet()
    if (groupIds.isEmpty()) return@transaction ServerVisibility(false, emptySet(), emptySet())
    val viewGroups = GroupPermissions.selectAll()
        .where { GroupPermissions.groupId inList groupIds }
        .filter { permGrantsServerView(it[GroupPermissions.permission]) }
        .map { it[GroupPermissions.groupId] }
        .toSet()
    if (viewGroups.isEmpty()) return@transaction ServerVisibility(false, emptySet(), emptySet())
    var isGlobal = false
    val networkIds = mutableSetOf<kotlin.uuid.Uuid>()
    val serverIds = mutableSetOf<kotlin.uuid.Uuid>()
    for (a in assignments.filter { it[UserGroupAssignments.groupId] in viewGroups }) {
        when (a[UserGroupAssignments.scopeType]) {
            ScopeType.GLOBAL.name  -> isGlobal = true
            ScopeType.NETWORK.name -> a[UserGroupAssignments.scopeId]?.let { networkIds += it }
            ScopeType.SERVER.name  -> a[UserGroupAssignments.scopeId]?.let { serverIds += it }
        }
    }
    ServerVisibility(isGlobal, networkIds, serverIds)
}

private fun permGrantsServerView(granted: String) =
    granted == "*" || granted == "server.*" || granted == Permission.SERVER_VIEW.node

internal fun rowToServerResponse(row: ResultRow, isMigrating: Boolean): ServerResponse {
    val customHostname = row[Servers.customHostname]
    val managedHostname = if (row[Servers.exposedExternally] && row[Servers.publicSubdomain] != null) {
        row[Servers.dnsRecordName]
    }
    else null
    val canonicalHostname = customHostname ?: managedHostname
    return ServerResponse(
        id = row[Servers.id].toString(),
        name = row[Servers.name],
        displayName = row[Servers.displayName],
        description = row[Servers.description],
        serverType = row[Servers.serverType],
        mcVersion = row[Servers.mcVersion],
        itzgImageTag = row[Servers.itzgImageTag],
        status = ServerStatus.fromDb(row[Servers.status]),
        nodeId = row[Servers.nodeId].toString(),
        networkId = row[Servers.networkId]?.toString(),
        hostPort = row[Servers.hostPort],
        memoryMb = row[Servers.memoryMb],
        cpuShares = row[Servers.cpuShares],
        exposedExternally = row[Servers.exposedExternally],
        publicSubdomain = row[Servers.publicSubdomain],
        customHostname = customHostname,
        canonicalHostname = canonicalHostname,
        isMigrating = isMigrating,
        needsRecreate = row[Servers.needsRecreate],
        configMode = ConfigMode.fromDb(row[Servers.configMode]),
        stopCommand = row[Servers.stopCommand],
        lastPlayerCount = row[Servers.lastPlayerCount],
        lastPlayerNames = row[Servers.lastPlayerNames]?.split(",")
            ?.filter { it.isNotBlank() },
        createdAt = row[Servers.createdAt].toUtcString(),
        updatedAt = row[Servers.updatedAt].toUtcString(),
    )
}

internal val PROXY_SERVER_TYPES = setOf("VELOCITY", "BUNGEECORD", "WATERFALL")


private fun parseUuid(raw: String): Uuid? =
    runCatching {
        Uuid.parse(raw)
    }.getOrNull()
