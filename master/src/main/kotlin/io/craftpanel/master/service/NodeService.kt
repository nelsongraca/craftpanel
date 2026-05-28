package io.craftpanel.master.service

import com.craftpanel.agent.v1.MasterMessage
import com.craftpanel.agent.v1.masterMessage
import com.craftpanel.agent.v1.shutdownCommand
import io.craftpanel.master.database.schema.NodeMetrics
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

@Serializable
data class NodeResponse(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val hostname: String,
    @SerialName("public_ip") val publicIp: String,
    @SerialName("private_ip") val privateIp: String,
    val status: String,
    @SerialName("total_ram_mb") val totalRamMb: Int,
    @SerialName("total_cpu_shares") val totalCpuShares: Int,
    @SerialName("allocated_ram_mb") val allocatedRamMb: Int,
    @SerialName("allocated_cpu_shares") val allocatedCpuShares: Int,
    @SerialName("port_range_start") val portRangeStart: Int,
    @SerialName("port_range_end") val portRangeEnd: Int,
    @SerialName("data_path") val dataPath: String,
    @SerialName("agent_version") val agentVersion: String?,
    @SerialName("last_seen_at") val lastSeenAt: String?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class PatchNodeRequest(
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("port_range_start") val portRangeStart: Int? = null,
    @SerialName("port_range_end") val portRangeEnd: Int? = null,
    @SerialName("data_path") val dataPath: String? = null,
)

@Serializable
data class NodeMetricsResponse(
    val timestamps: List<String>,
    @SerialName("cpu_percent") val cpuPercent: List<Double>,
    @SerialName("ram_used_mb") val ramUsedMb: List<Int>,
    @SerialName("ram_total_mb") val ramTotalMb: List<Int>,
    @SerialName("net_in_bytes") val netInBytes: List<Long>,
    @SerialName("net_out_bytes") val netOutBytes: List<Long>,
    @SerialName("disk_used_bytes") val diskUsedBytes: List<Long>,
    @SerialName("disk_total_bytes") val diskTotalBytes: List<Long>,
)

class NodeService(private val sendToNode: (String, MasterMessage) -> Boolean) {

    fun listNodes(): List<NodeResponse> = transaction {
        Nodes.selectAll()
            .map { row ->
                row.toNodeResponse(allocationsForNode(row[Nodes.id]))
            }
    }

    fun getNode(id: kotlin.uuid.Uuid): NodeResponse =
        transaction {
            Nodes.selectAll()
                .where { Nodes.id eq id }
                .firstOrNull()
                ?.let { it.toNodeResponse(allocationsForNode(id)) }
        } ?: throw NotFoundException("Node not found")

    fun trustNode(id: kotlin.uuid.Uuid) {
        val updated = transaction { Nodes.update({ Nodes.id eq id }) { it[status] = "ACTIVE" } }
        if (updated == 0) throw NotFoundException("Node not found")
    }

    fun rejectNode(id: kotlin.uuid.Uuid) {
        val updated = transaction { Nodes.update({ Nodes.id eq id }) { it[status] = "REJECTED" } }
        if (updated == 0) throw NotFoundException("Node not found")
    }

    fun rotateToken(id: kotlin.uuid.Uuid): String {
        val (rawKey, updated) = transaction {
            val exists = Nodes.selectAll()
                .where { Nodes.id eq id }
                .firstOrNull() != null
            if (!exists) return@transaction null to 0
            val raw = generateNodeKey()
            val hash = sha256Hex(raw)
            val rows = Nodes.update({ Nodes.id eq id }) { it[tokenHash] = hash }
            raw to rows
        }
        if (updated == 0) throw NotFoundException("Node not found")
        return rawKey!!
    }

    fun shutdownNode(id: kotlin.uuid.Uuid) {
        val exists = transaction {
            Nodes.selectAll()
                .where { Nodes.id eq id }
                .firstOrNull() != null
        }
        if (!exists) throw NotFoundException("Node not found")
        val msg = masterMessage { shutdown = shutdownCommand { timeoutSeconds = 30 } }
        if (!sendToNode(id.toString(), msg)) throw BadGatewayException("Agent not connected")
    }

    fun updateNode(id: kotlin.uuid.Uuid, req: PatchNodeRequest) {
        val result = transaction {
            val current = Nodes.selectAll()
                .where { Nodes.id eq id }
                .firstOrNull()
                ?: return@transaction null
            val newStart = req.portRangeStart ?: current[Nodes.portRangeStart]
            val newEnd = req.portRangeEnd ?: current[Nodes.portRangeEnd]
            if (newStart >= newEnd) return@transaction "Port range start must be less than end"
            Nodes.update({ Nodes.id eq id }) {
                if (req.displayName != null) it[displayName] = req.displayName
                it[portRangeStart] = newStart
                it[portRangeEnd] = newEnd
                if (req.dataPath != null) it[dataPath] = req.dataPath
            }
            "ok"
        }
        when (result) {
            null -> throw NotFoundException("Node not found")
            "ok" -> Unit
            else -> throw UnprocessableException(result)
        }
    }

    fun decommissionNode(id: kotlin.uuid.Uuid) {
        val updated = transaction { Nodes.update({ Nodes.id eq id }) { it[status] = "DECOMMISSIONED" } }
        if (updated == 0) throw NotFoundException("Node not found")
    }

    fun getNodeMetrics(id: kotlin.uuid.Uuid, limit: Int): NodeMetricsResponse {
        val exists = transaction {
            Nodes.selectAll()
                .where { Nodes.id eq id }
                .firstOrNull() != null
        }
        if (!exists) throw NotFoundException("Node not found")
        val metrics = transaction {
            NodeMetrics.selectAll()
                .where { NodeMetrics.nodeId eq id }
                .orderBy(NodeMetrics.recordedAt, SortOrder.DESC)
                .limit(limit)
                .toList()
                .reversed()
        }
        return NodeMetricsResponse(
            timestamps = metrics.map { it[NodeMetrics.recordedAt].toString() },
            cpuPercent = metrics.map { it[NodeMetrics.cpuPercent] },
            ramUsedMb = metrics.map { it[NodeMetrics.ramUsedMb] },
            ramTotalMb = metrics.map { it[NodeMetrics.ramTotalMb] },
            netInBytes = metrics.map { it[NodeMetrics.netInBytes] },
            netOutBytes = metrics.map { it[NodeMetrics.netOutBytes] },
            diskUsedBytes = metrics.map { it[NodeMetrics.diskUsedBytes] },
            diskTotalBytes = metrics.map { it[NodeMetrics.diskTotalBytes] },
        )
    }
}

private data class NodeAllocations(val ramMb: Int, val cpuShares: Int)

private fun allocationsForNode(nodeKotlinId: kotlin.uuid.Uuid): NodeAllocations {
    val rows = Servers.selectAll()
        .where { Servers.nodeId eq nodeKotlinId }
    var ram = 0;
    var cpu = 0
    for (row in rows) {
        ram += row[Servers.memoryMb]; cpu += row[Servers.cpuShares]
    }
    return NodeAllocations(ram, cpu)
}

private fun org.jetbrains.exposed.v1.core.ResultRow.toNodeResponse(alloc: NodeAllocations) = NodeResponse(
    id = this[Nodes.id].toString(),
    displayName = this[Nodes.displayName],
    hostname = this[Nodes.hostname],
    publicIp = this[Nodes.publicIp],
    privateIp = this[Nodes.privateIp],
    status = this[Nodes.status],
    totalRamMb = this[Nodes.totalRamMb],
    totalCpuShares = this[Nodes.totalCpuShares],
    allocatedRamMb = alloc.ramMb,
    allocatedCpuShares = alloc.cpuShares,
    portRangeStart = this[Nodes.portRangeStart],
    portRangeEnd = this[Nodes.portRangeEnd],
    dataPath = this[Nodes.dataPath],
    agentVersion = this[Nodes.agentVersion],
    lastSeenAt = this[Nodes.lastSeenAt]?.toString(),
    createdAt = this[Nodes.createdAt].toString(),
    updatedAt = this[Nodes.updatedAt].toString(),
)

private fun generateNodeKey(): String {
    val bytes = ByteArray(32).also {
        java.security.SecureRandom()
            .nextBytes(it)
    }
    return java.util.Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(bytes)
}

private fun sha256Hex(input: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    return digest.digest(input.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
