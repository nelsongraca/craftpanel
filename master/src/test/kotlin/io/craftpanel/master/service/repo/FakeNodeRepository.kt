package io.craftpanel.master.service.repo

import io.craftpanel.master.domain.NodeHealth
import io.craftpanel.master.domain.NodeStatus
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

    fun addNode(
        displayName: String = "node-1",
        hostname: String = "host",
        publicIp: String = "1.2.3.4",
        privateIp: String = "10.0.0.1",
        tokenHash: String = "hash",
        portRangeStart: Int = 25570,
        portRangeEnd: Int = 26070,
        totalRamMb: Int = 0,
        totalCpuShares: Int = 0,
        agentVersion: String? = null,
        lastSeenAt: String? = null,
        id: Uuid = Uuid.random()
    ): NodeRow {
        val n = MutableNode(id, displayName, hostname, publicIp, privateIp, tokenHash, portRangeStart = portRangeStart, portRangeEnd = portRangeEnd, totalRamMb = totalRamMb, totalCpuShares = totalCpuShares, agentVersion = agentVersion, lastSeenAt = lastSeenAt)
        nodes[id] = n
        return n.toRow()
    }

    fun create(
        displayName: String,
        hostname: String,
        publicIp: String,
        privateIp: String,
        tokenHash: String,
        portRangeStart: Int,
        portRangeEnd: Int,
        totalRamMb: Int = 0,
        totalCpuShares: Int = 0,
        agentVersion: String? = null,
        lastSeenAt: kotlin.time.Instant? = null
    ): NodeRow = addNode(displayName = displayName, hostname = hostname, publicIp = publicIp, privateIp = privateIp, tokenHash = tokenHash, portRangeStart = portRangeStart, portRangeEnd = portRangeEnd, totalRamMb = totalRamMb, totalCpuShares = totalCpuShares, agentVersion = agentVersion, lastSeenAt = lastSeenAt?.toString())

    fun updateStatus(id: Uuid, status: NodeStatus) {
        nodes[id]?.status = status.toDb()
    }

    override fun findById(id: Uuid): NodeRow? = nodes[id]?.toRow()
    override fun findByTokenHash(tokenHash: String): NodeRow? = nodes.values.firstOrNull { it.tokenHash == tokenHash }
        ?.toRow()

    override fun listAll(): List<NodeRow> = nodes.values.map { it.toRow() }
    override fun listByIds(ids: List<Uuid>): List<NodeRow> = ids.mapNotNull { nodes[it]?.toRow() }

    override fun calculateAllocatedRam(id: Uuid): Int = allocatedRam(id)
    override fun calculateAllocatedCpu(id: Uuid): Int = allocatedCpu(id)

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