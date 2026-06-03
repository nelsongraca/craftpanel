package io.craftpanel.master.service

import com.craftpanel.agent.v1.*
import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.ScopeType
import io.craftpanel.master.database.schema.*
import io.craftpanel.master.config.ImagesConfig
import io.craftpanel.master.dns.DnsProvider
import org.jetbrains.exposed.v1.core.ResultRow
import io.craftpanel.master.util.toKotlinUuid
import org.slf4j.LoggerFactory
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.*
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
    val status: String,
    @SerialName("node_id") val nodeId: String,
    @SerialName("network_id") val networkId: String?,
    @SerialName("host_port") val hostPort: Int,
    @SerialName("memory_mb") val memoryMb: Int,
    @SerialName("cpu_shares") val cpuShares: Int,
    @SerialName("exposed_externally") val exposedExternally: Boolean,
    @SerialName("public_subdomain") val publicSubdomain: String?,
    @SerialName("is_migrating") val isMigrating: Boolean,
    @SerialName("config_mode") val configMode: String,
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
data class PatchResourcesRequest(
    @SerialName("memory_mb") val memoryMb: Int,
    @SerialName("cpu_shares") val cpuShares: Int,
    @SerialName("itzg_image_tag") val itzgImageTag: String? = null,
)

@Serializable
data class PatchExposureRequest(
    @SerialName("exposed_externally") val exposedExternally: Boolean,
    @SerialName("public_subdomain") val publicSubdomain: String? = null,
)

@Serializable
data class UpgradeServerRequest(
    @SerialName("itzg_image_tag") val itzgImageTag: String,
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

data class ServerAuthInfo(val networkId: UUID?)

class ServerService(
    private val sendToNode: (String, MasterMessage) -> Boolean,
    private val modService: ModService,
    private val dnsProvider: DnsProvider? = null,
    private val images: ImagesConfig = ImagesConfig("itzg/minecraft-server", "itzg/mc-proxy"),
) {

    private val log = LoggerFactory.getLogger(ServerService::class.java)

    private fun deriveImage(serverType: String, tag: String): String = when (serverType) {
        "BUNGEECORD", "VELOCITY", "WATERFALL" -> "${images.proxyImage}:$tag"
        else                                  -> "${images.minecraftImage}:$tag"
    }

    fun authInfo(id: kotlin.uuid.Uuid): ServerAuthInfo? = transaction {
        Servers.selectAll()
            .where { Servers.id eq id }
            .firstOrNull()
            ?.let { ServerAuthInfo(it[Servers.networkId]?.let { nid -> UUID.fromString(nid.toString()) }) }
    }

    fun listServers(userId: UUID): List<ServerResponse> {
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

            val stopCommand = if (req.serverType in listOf("VELOCITY", "BUNGEECORD", "WATERFALL")) "end" else "stop"
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

    fun updateServer(id: kotlin.uuid.Uuid, body: JsonObject) {
        val displayNameKey = "display_name" in body
        val descriptionKey = "description" in body
        val networkIdKey = "network_id" in body
        val mcVersionKey = "mc_version" in body

        val newDisplayName = if (displayNameKey && body["display_name"] !is JsonNull) body["display_name"]!!.jsonPrimitive.content else null
        val newDescription = if (descriptionKey && body["description"] !is JsonNull) body["description"]!!.jsonPrimitive.content else null
        val newNetworkId: kotlin.uuid.Uuid? = if (networkIdKey && body["network_id"] !is JsonNull) {
            parseUuid(body["network_id"]!!.jsonPrimitive.content) ?: throw UnprocessableException("Invalid network_id")
        }
        else null
        val newMcVersion = if (mcVersionKey && body["mc_version"] !is JsonNull) body["mc_version"]!!.jsonPrimitive.content else null

        val result = transaction {
            Servers.selectAll()
                .where { Servers.id eq id }
                .firstOrNull()
                ?: return@transaction "not_found"
            if (networkIdKey && newNetworkId != null) {
                val netExists = ServerNetworks.selectAll()
                    .where { ServerNetworks.id eq newNetworkId }
                    .firstOrNull() != null
                if (!netExists) return@transaction "network_not_found"
            }
            Servers.update({ Servers.id eq id }) {
                if (displayNameKey && newDisplayName != null) it[displayName] = newDisplayName
                if (descriptionKey) it[description] = newDescription
                if (networkIdKey) it[networkId] = newNetworkId
                if (mcVersionKey && newMcVersion != null) it[mcVersion] = newMcVersion
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

        transaction {
            PortRegistry.deleteWhere { PortRegistry.serverId eq id }
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
            val systemRamUsed = node[Nodes.systemRamUsedMb] ?: 0
            val effectiveUsedRam = maxOf(usedRam, systemRamUsed)
            if (effectiveUsedRam + req.memoryMb > node[Nodes.totalRamMb]) return@transaction "insufficient_ram"
            if (node[Nodes.totalCpuShares] > 0 && usedCpu + req.cpuShares > node[Nodes.totalCpuShares]) return@transaction "insufficient_cpu"
            Servers.update({ Servers.id eq id }) {
                it[memoryMb] = req.memoryMb
                it[cpuShares] = req.cpuShares
                if (req.itzgImageTag != null) it[itzgImageTag] = req.itzgImageTag
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
        val currentStatus = serverRow[Servers.status]
        if (currentStatus == "HEALTHY" || currentStatus == "STARTING")
            throw ConflictException("Server is already running")
        val nodeKotlinId = serverRow[Servers.nodeId]
        val serverType = serverRow[Servers.serverType]
        val serverImage = deriveImage(serverType, serverRow[Servers.itzgImageTag])
        val allVars = buildAllVars(id, serverRow)
        val nodeId = nodeKotlinId.toString()
        val publicHostname = serverRow[Servers.dnsRecordName]
            ?: if (serverRow[Servers.exposedExternally] && serverRow[Servers.publicSubdomain] != null) {
                resolvePublicHostname(serverRow[Servers.publicSubdomain]!!, serverRow[Servers.networkId])
            }
            else null

        if (serverRow[Servers.containerId] == null) {
            val createCmd = buildCreateContainerCommand(id, serverRow, serverImage, allVars, publicHostname)
            if (!sendToNode(nodeId, createCmd)) throw BadGatewayException("Agent not connected")
        }

        val startCmd = masterMessage { startContainer = startContainerCommand { serverId = id.toString(); containerName = "craftpanel-$id" } }
        if (!sendToNode(nodeId, startCmd)) throw BadGatewayException("Agent not connected")

        transaction {
            Servers.update({ Servers.id eq id }) {
                it[status] = "STARTING"; it[updatedAt] = Clock.System.now()
                .toLocalDateTime(TimeZone.UTC)
            }
        }
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
        val stopCmd = masterMessage {
            stopContainer = stopContainerCommand {
                serverId = id.toString(); containerName = "craftpanel-$id"
                timeoutSeconds = 30; stopCommand = serverRow[Servers.stopCommand]
            }
        }
        if (!sendToNode(nodeId, stopCmd)) throw BadGatewayException("Agent not connected")
        transaction {
            Servers.update({ Servers.id eq id }) {
                it[status] = "STOPPING"; it[updatedAt] = Clock.System.now()
                .toLocalDateTime(TimeZone.UTC)
            }
        }
    }

    fun restartServer(id: kotlin.uuid.Uuid) {
        val serverRow = transaction {
            Servers.selectAll()
                .where { Servers.id eq id }
                .firstOrNull()
        }
            ?: throw NotFoundException("Server not found")
        val nodeId = serverRow[Servers.nodeId].toString()
        val restartCmd = masterMessage {
            restartContainer = restartContainerCommand {
                serverId = id.toString(); containerName = "craftpanel-$id"
                timeoutSeconds = 30; stopCommand = serverRow[Servers.stopCommand]
            }
        }
        if (!sendToNode(nodeId, restartCmd)) throw BadGatewayException("Agent not connected")
    }

    fun upgradeServer(id: kotlin.uuid.Uuid, req: UpgradeServerRequest) {
        if (req.itzgImageTag.isBlank()) throw UnprocessableException("itzg_image_tag must not be blank")
        val serverRow = transaction {
            Servers.selectAll()
                .where { Servers.id eq id }
                .firstOrNull()
        }
            ?: throw NotFoundException("Server not found")
        if (serverRow[Servers.status] != "STOPPED") throw ConflictException("Server must be STOPPED before upgrade")
        val nodeId = serverRow[Servers.nodeId].toString()
        val serverImage = deriveImage(serverRow[Servers.serverType], req.itzgImageTag)
        val allVars = buildAllVars(id, serverRow)
        val publicHostname = serverRow[Servers.dnsRecordName]

        if (serverRow[Servers.containerId] != null) {
            val removeCmd = masterMessage { removeContainer = removeContainerCommand { serverId = id.toString(); containerName = "craftpanel-$id"; force = false } }
            if (!sendToNode(nodeId, removeCmd)) throw BadGatewayException("Agent not connected")
        }
        val pullCmd = masterMessage { pullImage = pullImageCommand { serverId = id.toString(); image = serverImage } }
        if (!sendToNode(nodeId, pullCmd)) throw BadGatewayException("Agent not connected")
        val createCmd = buildCreateContainerCommand(id, serverRow, serverImage, allVars, publicHostname)
        if (!sendToNode(nodeId, createCmd)) throw BadGatewayException("Agent not connected")
        transaction {
            Servers.update({ Servers.id eq id }) {
                it[Servers.itzgImageTag] = req.itzgImageTag
                it[Servers.containerId] = null
                it[updatedAt] = Clock.System.now()
                    .toLocalDateTime(TimeZone.UTC)
            }
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

        val existingRecordId = serverRow[Servers.dnsRecordId]

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

            val recordId = if (provider != null && dns != null) {
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

            transaction {
                Servers.update({ Servers.id eq id }) {
                    it[exposedExternally] = true
                    it[publicSubdomain] = req.publicSubdomain
                    it[dnsRecordName] = fullHostname
                    it[dnsRecordId] = recordId
                    it[updatedAt] = Clock.System.now()
                        .toLocalDateTime(TimeZone.UTC)
                }
            }

            val isRunning = serverRow[Servers.status] in listOf("HEALTHY", "STARTING", "UNHEALTHY")
            if (isRunning) {
                val nodeId = serverRow[Servers.nodeId].toString()
                val allVars = buildAllVars(id, serverRow)
                val serverImage = deriveImage(serverRow[Servers.serverType], serverRow[Servers.itzgImageTag])
                sendToNode(
                    nodeId,
                    masterMessage {
                        stopContainer = stopContainerCommand { serverId = id.toString(); containerName = "craftpanel-$id"; timeoutSeconds = 30; stopCommand = serverRow[Servers.stopCommand] }
                    })
                sendToNode(nodeId, masterMessage { removeContainer = removeContainerCommand { serverId = id.toString(); containerName = "craftpanel-$id"; force = false } })
                sendToNode(nodeId, buildCreateContainerCommand(id, serverRow, serverImage, allVars, fullHostname))
                sendToNode(nodeId, masterMessage { startContainer = startContainerCommand { serverId = id.toString(); containerName = "craftpanel-$id" } })
            }
        }
        else {
            val provider = dnsProvider
            if (existingRecordId != null && provider != null) {
                val dns = resolveNetworkDns(serverRow[Servers.networkId])
                if (dns != null) {
                    runCatching { provider.deleteARecord(dns.zoneId, existingRecordId) }
                        .onFailure { log.warn("Failed to delete DNS record $existingRecordId — continuing", it) }
                }
            }
            transaction {
                Servers.update({ Servers.id eq id }) {
                    it[exposedExternally] = false
                    it[publicSubdomain] = null
                    it[dnsRecordName] = null
                    it[dnsRecordId] = null
                    it[updatedAt] = Clock.System.now()
                        .toLocalDateTime(TimeZone.UTC)
                }
            }
            val isRunning = serverRow[Servers.status] in listOf("HEALTHY", "STARTING", "UNHEALTHY")
            if (isRunning) {
                val nodeId = serverRow[Servers.nodeId].toString()
                val allVars = buildAllVars(id, serverRow)
                val serverImage = deriveImage(serverRow[Servers.serverType], serverRow[Servers.itzgImageTag])
                sendToNode(
                    nodeId,
                    masterMessage {
                        stopContainer = stopContainerCommand { serverId = id.toString(); containerName = "craftpanel-$id"; timeoutSeconds = 30; stopCommand = serverRow[Servers.stopCommand] }
                    })
                sendToNode(nodeId, masterMessage { removeContainer = removeContainerCommand { serverId = id.toString(); containerName = "craftpanel-$id"; force = false } })
                sendToNode(nodeId, buildCreateContainerCommand(id, serverRow, serverImage, allVars, null))
                sendToNode(nodeId, masterMessage { startContainer = startContainerCommand { serverId = id.toString(); containerName = "craftpanel-$id" } })
            }
        }
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

    private fun buildAllVars(id: kotlin.uuid.Uuid, serverRow: ResultRow): Map<String, String> {
        val serverType = serverRow[Servers.serverType]
        val modrinthProjects = modService.buildModrinthEnvVar(id)
        val dbEnvVars = transaction {
            ServerEnvVars.selectAll()
                .where { ServerEnvVars.serverId eq id }
                .associate { it[ServerEnvVars.key] to it[ServerEnvVars.value] }
        }
        val systemVars = buildMap {
            put("EULA", "TRUE"); put("TYPE", serverType)
            put("VERSION", serverRow[Servers.mcVersion]); put("MEMORY", "${serverRow[Servers.memoryMb]}M")
            if (modrinthProjects.isNotEmpty()) put("MODRINTH_PROJECTS", modrinthProjects)
        }
        return systemVars + dbEnvVars
    }

    private fun buildCreateContainerCommand(
        serverId: kotlin.uuid.Uuid,
        serverRow: ResultRow,
        image: String,
        allVars: Map<String, String>,
        publicHostname: String?,
    ): MasterMessage = masterMessage {
        createContainer = createContainerCommand {
            this.serverId = serverId.toString()
            containerName = "craftpanel-$serverId"
            this.image = image
            ramMb = serverRow[Servers.memoryMb]
            cpuShares = serverRow[Servers.cpuShares]
            hostPort = serverRow[Servers.hostPort]
            envVars.putAll(allVars)
            dockerNetwork = serverRow[Servers.networkId]?.let { "craftpanel-net-$it" } ?: ""
            restartPolicy = "unless-stopped"
            stopCommand = serverRow[Servers.stopCommand]
            mcRouterHostname = publicHostname ?: ""
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

internal fun resolveServerVisibility(userId: UUID): ServerVisibility = transaction {
    val kotlinUserId = userId.toKotlinUuid()
    val user = Users.selectAll()
        .where { Users.id eq kotlinUserId }
        .firstOrNull()
    if (user == null || !user[Users.isActive]) return@transaction ServerVisibility(false, emptySet(), emptySet())
    val assignments = UserGroupAssignments.selectAll()
        .where { UserGroupAssignments.userId eq kotlinUserId }
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

internal fun rowToServerResponse(row: ResultRow, isMigrating: Boolean) = ServerResponse(
    id = row[Servers.id].toString(),
    name = row[Servers.name],
    displayName = row[Servers.displayName],
    description = row[Servers.description],
    serverType = row[Servers.serverType],
    mcVersion = row[Servers.mcVersion],
    itzgImageTag = row[Servers.itzgImageTag],
    status = row[Servers.status],
    nodeId = row[Servers.nodeId].toString(),
    networkId = row[Servers.networkId]?.toString(),
    hostPort = row[Servers.hostPort],
    memoryMb = row[Servers.memoryMb],
    cpuShares = row[Servers.cpuShares],
    exposedExternally = row[Servers.exposedExternally],
    publicSubdomain = row[Servers.publicSubdomain],
    isMigrating = isMigrating,
    configMode = row[Servers.configMode],
    lastPlayerCount = row[Servers.lastPlayerCount],
    lastPlayerNames = row[Servers.lastPlayerNames]?.split(",")
        ?.filter { it.isNotBlank() },
    createdAt = row[Servers.createdAt].toUtcString(),
    updatedAt = row[Servers.updatedAt].toUtcString(),
)

internal val PROXY_SERVER_TYPES = setOf("VELOCITY", "BUNGEECORD", "WATERFALL")


private fun parseUuid(raw: String): kotlin.uuid.Uuid? =
    runCatching {
        UUID.fromString(raw)
            .toKotlinUuid()
    }.getOrNull()
