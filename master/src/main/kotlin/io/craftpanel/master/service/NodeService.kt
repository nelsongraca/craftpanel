package io.craftpanel.master.service

import io.craftpanel.proto.masterMessage
import io.craftpanel.proto.shutdownCommand
import io.craftpanel.master.domain.NodeHealth
import io.craftpanel.master.domain.NodeStatus
import io.craftpanel.master.service.repo.NodeRepository
import io.craftpanel.master.service.repo.NodeRow
import io.craftpanel.master.service.repo.ServerRepository
import io.craftpanel.master.util.CryptoUtils
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.HexFormat

@Serializable
data class NodeResponse(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val hostname: String,
    @SerialName("public_ip") val publicIp: String,
    @SerialName("private_ip") val privateIp: String,
    val status: NodeStatus,
    val health: NodeHealth,
    @SerialName("total_ram_mb") val totalRamMb: Int,
    @SerialName("total_cpu_shares") val totalCpuShares: Int,
    @SerialName("allocated_ram_mb") val allocatedRamMb: Int,
    @SerialName("allocated_cpu_shares") val allocatedCpuShares: Int,
    @SerialName("system_ram_used_mb") val systemRamUsedMb: Int?,
    @SerialName("port_range_start") val portRangeStart: Int,
    @SerialName("port_range_end") val portRangeEnd: Int,
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

class NodeService(
    private val gateway: AgentGateway,
    private val nodeRepository: NodeRepository,
    private val serverRepository: ServerRepository,
) {

    fun listNodes(): List<NodeResponse> {
        return nodeRepository.listAll()
            .map { node ->
                val ram = nodeRepository.calculateAllocatedRam(node.id)
                val cpu = nodeRepository.calculateAllocatedCpu(node.id)
                node.toNodeResponse(ram, cpu)
            }
    }

    fun getNode(id: kotlin.uuid.Uuid): NodeResponse {
        val node = nodeRepository.findById(id) ?: throw NotFoundException("Node not found")
        val ram = nodeRepository.calculateAllocatedRam(id)
        val cpu = nodeRepository.calculateAllocatedCpu(id)
        return node.toNodeResponse(ram, cpu)
    }

    fun trustNode(id: kotlin.uuid.Uuid) {
        val node = nodeRepository.findById(id) ?: throw NotFoundException("Node not found")
        if (node.status == "ACTIVE") throw ConflictException("Node is already active")
        nodeRepository.updateStatus(id, "ACTIVE")
        nodeRepository.updateHealth(id, "UNREACHABLE")
    }

    fun rejectNode(id: kotlin.uuid.Uuid) {
        val node = nodeRepository.findById(id) ?: throw NotFoundException("Node not found")
        if (node.status == "ACTIVE") throw ConflictException("Cannot reject an active node")
        nodeRepository.updateStatus(id, "REJECTED")
    }

    fun rotateToken(id: kotlin.uuid.Uuid): String {
        if (nodeRepository.findById(id) == null) throw NotFoundException("Node not found")
        val raw = generateNodeKey()
        val hash = sha256Hex(raw)
        nodeRepository.updateTokenHash(id, hash)
        return raw
    }

    fun shutdownNode(id: kotlin.uuid.Uuid) {
        if (nodeRepository.findById(id) == null) throw NotFoundException("Node not found")
        val msg = masterMessage { shutdown = shutdownCommand { timeoutSeconds = 30 } }
        if (!gateway.sendToNode(id.toString(), msg)) throw BadGatewayException("Agent not connected")
    }

    fun updateNode(id: kotlin.uuid.Uuid, req: PatchNodeRequest) {
        val node = nodeRepository.findById(id) ?: throw NotFoundException("Node not found")
        val newStart = req.portRangeStart ?: node.portRangeStart
        val newEnd = req.portRangeEnd ?: node.portRangeEnd
        if (newStart >= newEnd) throw UnprocessableException("Port range start must be less than end")
        nodeRepository.update(id, req.displayName, newStart, newEnd)
    }

    fun decommissionNode(id: kotlin.uuid.Uuid) {
        val node = nodeRepository.findById(id) ?: throw NotFoundException("Node not found")
        if (node.status != "ACTIVE" && node.status != "PENDING") throw ConflictException("Node cannot be decommissioned")
        if (serverRepository.countByNodeId(id) > 0) throw ConflictException("Node has active servers")
        nodeRepository.markDecommissioned(id)
    }

    fun getNodeMetrics(id: kotlin.uuid.Uuid, limit: Int): NodeMetricsResponse {
        if (nodeRepository.findById(id) == null) throw NotFoundException("Node not found")
        val metrics = nodeRepository.getMetrics(id, limit)
            .reversed()
        return NodeMetricsResponse(
            timestamps = metrics.map { it.recordedAt },
            cpuPercent = metrics.map { it.cpuPercent },
            ramUsedMb = metrics.map { it.ramUsedMb },
            ramTotalMb = metrics.map { it.ramTotalMb },
            netInBytes = metrics.map { it.netInBytes },
            netOutBytes = metrics.map { it.netOutBytes },
            diskUsedBytes = metrics.map { it.diskUsedBytes },
            diskTotalBytes = metrics.map { it.diskTotalBytes },
        )
    }
}

private fun NodeRow.toNodeResponse(allocatedRamMb: Int, allocatedCpuShares: Int) = NodeResponse(
    id = id.toString(),
    displayName = displayName,
    hostname = hostname,
    publicIp = publicIp,
    privateIp = privateIp,
    status = NodeStatus.fromDb(status),
    health = NodeHealth.valueOf(health),
    totalRamMb = totalRamMb,
    totalCpuShares = totalCpuShares,
    allocatedRamMb = allocatedRamMb,
    allocatedCpuShares = allocatedCpuShares,
    systemRamUsedMb = systemRamUsedMb,
    portRangeStart = portRangeStart,
    portRangeEnd = portRangeEnd,
    agentVersion = agentVersion,
    lastSeenAt = lastSeenAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun generateNodeKey(): String = CryptoUtils.generateToken(32)

private fun sha256Hex(input: String): String =
    HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray())
        )
