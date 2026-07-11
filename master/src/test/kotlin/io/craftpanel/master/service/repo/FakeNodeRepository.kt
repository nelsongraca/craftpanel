package io.craftpanel.master.service.repo

import io.craftpanel.master.domain.NodeStatus
import kotlinx.datetime.Instant
import kotlin.uuid.Uuid

class FakeNodeRepository : NodeRepository {

    private val nodes = mutableMapOf<Uuid, MutableNode>()
    private val metrics = mutableListOf<MutableNodeMetrics>()

    data class MutableNode(
        val id: Uuid,
        var displayName: String,
        var hostname: String,
        var publicIp: String,
        var privateIp: String,
        var tokenHash: String,
        var status: String = "PENDING",
        var health: String = "HEALTHY",
        var totalRamMb: Int = 0,
        var totalCpuShares: Int = 0,
        var systemRamUsedMb: Int? = null,
        var reservedRamMb: Int = 1024,
        var portRangeStart: Int = 25570,
        var portRangeEnd: Int = 26070,
        var swarmActive: Boolean = false,
        var agentVersion: String? = null,
        var lastSeenAt: String? = null,
        var createdAt: String = "2025-01-01T00:00:00Z",
        var updatedAt: String = "2025-01-01T00:00:00Z"
    )

    data class MutableNodeMetrics(
        val nodeId: Uuid,
        val recordedAt: String,
        val cpuPercent: Double,
        val ramUsedMb: Int,
        val ramTotalMb: Int,
        val netInBytes: Long,
        val netOutBytes: Long,
        val diskUsedBytes: Long,
        val diskTotalBytes: Long
    )

    private var allocatedRam: (Uuid) -> Int = { 0 }
    private var allocatedCpu: (Uuid) -> Int = { 0 }

    fun setAllocations(ram: (Uuid) -> Int, cpu: (Uuid) -> Int) {
        allocatedRam = ram
        allocatedCpu = cpu
    }

    fun setCapacity(id: Uuid, totalRamMb: Int, totalCpuShares: Int = 0, systemRamUsedMb: Int? = null, reservedRamMb: Int = 1024) {
        nodes[id]?.let {
            it.totalRamMb = totalRamMb
            it.totalCpuShares = totalCpuShares
            it.systemRamUsedMb = systemRamUsedMb
            it.reservedRamMb = reservedRamMb
        }
    }

    override fun findById(id: Uuid): NodeRow? = nodes[id]?.toRow()
    override fun findByTokenHash(tokenHash: String): NodeRow? = nodes.values.firstOrNull { it.tokenHash == tokenHash }
        ?.toRow()

    override fun listAll(): List<NodeRow> = nodes.values.map { it.toRow() }
    override fun listByIds(ids: List<Uuid>): List<NodeRow> = ids.mapNotNull { nodes[it]?.toRow() }

    override fun create(
        displayName: String,
        hostname: String,
        publicIp: String,
        privateIp: String,
        tokenHash: String,
        portRangeStart: Int,
        portRangeEnd: Int,
        totalRamMb: Int,
        totalCpuShares: Int,
        agentVersion: String?,
        lastSeenAt: Instant?
    ): NodeRow {
        val id = Uuid.random()
        val n = MutableNode(
            id,
            displayName,
            hostname,
            publicIp,
            privateIp,
            tokenHash,
            portRangeStart = portRangeStart,
            portRangeEnd = portRangeEnd,
            totalRamMb = totalRamMb,
            totalCpuShares = totalCpuShares,
            agentVersion = agentVersion,
            lastSeenAt = lastSeenAt?.toString()
        )
        nodes[id] = n
        return n.toRow()
    }

    override fun update(id: Uuid, displayName: String?, portRangeStart: Int?, portRangeEnd: Int?, reservedRamMb: Int?) {
        val n = nodes[id] ?: return
        if (displayName != null) n.displayName = displayName
        if (portRangeStart != null) n.portRangeStart = portRangeStart
        if (portRangeEnd != null) n.portRangeEnd = portRangeEnd
        if (reservedRamMb != null) n.reservedRamMb = reservedRamMb
    }

    override fun updateStatus(id: Uuid, status: NodeStatus) {
        nodes[id]?.status = status.toDb()
    }

    override fun updateHealth(id: Uuid, health: String) {
        nodes[id]?.health = health
    }

    override fun updateLastSeen(id: Uuid, lastSeenAt: Instant, publicIp: String?, agentVersion: String?, privateIp: String?) {
        nodes[id]?.let {
            it.lastSeenAt = lastSeenAt.toString()
            if (publicIp != null) it.publicIp = publicIp
            if (agentVersion != null) it.agentVersion = agentVersion
            if (privateIp != null) it.privateIp = privateIp
        }
    }

    override fun updateSystemRam(id: Uuid, ramUsedMb: Int) {
        nodes[id]?.systemRamUsedMb = ramUsedMb
    }

    override fun updateSwarmActive(id: Uuid, swarmActive: Boolean) {
        nodes[id]?.swarmActive = swarmActive
    }

    override fun markUnreachable(id: Uuid, lastSeenAt: Instant?) {
        nodes[id]?.let {
            it.health = "UNREACHABLE"
            if (lastSeenAt != null) it.lastSeenAt = lastSeenAt.toString()
        }
    }

    override fun markDecommissioned(id: Uuid) {
        nodes[id]?.status = "DECOMMISSIONED"
    }

    override fun updateTokenHash(id: Uuid, tokenHash: String) {
        nodes[id]?.tokenHash = tokenHash
    }

    override fun calculateAllocatedRam(id: Uuid): Int = allocatedRam(id)
    override fun calculateAllocatedCpu(id: Uuid): Int = allocatedCpu(id)

    override fun insertMetrics(nodeId: Uuid, cpuPercent: Double, ramUsedMb: Int, ramTotalMb: Int, netInBytes: Long, netOutBytes: Long, diskUsedBytes: Long, diskTotalBytes: Long, recordedAt: Instant) {
        metrics.add(MutableNodeMetrics(nodeId, recordedAt.toString(), cpuPercent, ramUsedMb, ramTotalMb, netInBytes, netOutBytes, diskUsedBytes, diskTotalBytes))
    }

    override fun getMetrics(nodeId: Uuid, limit: Int): List<NodeMetricsRow> = metrics.filter { it.nodeId == nodeId }
        .take(limit)
        .map { NodeMetricsRow(Uuid.random(), it.nodeId, it.recordedAt, it.cpuPercent, it.ramUsedMb, it.ramTotalMb, it.netInBytes, it.netOutBytes, it.diskUsedBytes, it.diskTotalBytes) }

    private fun MutableNode.toRow() = NodeRow(
        id,
        displayName,
        hostname,
        publicIp,
        privateIp,
        tokenHash,
        status,
        health,
        totalRamMb,
        totalCpuShares,
        systemRamUsedMb,
        reservedRamMb,
        portRangeStart,
        portRangeEnd,
        swarmActive,
        agentVersion,
        lastSeenAt,
        createdAt,
        updatedAt
    )
}
