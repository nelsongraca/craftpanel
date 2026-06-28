package io.craftpanel.master.service.repo

import kotlinx.datetime.Instant
import kotlin.uuid.Uuid

data class NodeRow(
    val id: Uuid,
    val displayName: String,
    val hostname: String,
    val publicIp: String,
    val privateIp: String,
    val tokenHash: String,
    val status: String,
    val health: String,
    val totalRamMb: Int,
    val totalCpuShares: Int,
    val systemRamUsedMb: Int?,
    val portRangeStart: Int,
    val portRangeEnd: Int,
    val swarmActive: Boolean,
    val agentVersion: String?,
    val lastSeenAt: String?,
    val createdAt: String,
    val updatedAt: String,
)

data class NodeMetricsRow(
    val id: Uuid,
    val nodeId: Uuid,
    val recordedAt: String,
    val cpuPercent: Double,
    val ramUsedMb: Int,
    val ramTotalMb: Int,
    val netInBytes: Long,
    val netOutBytes: Long,
    val diskUsedBytes: Long,
    val diskTotalBytes: Long,
)

interface NodeRepository {

    fun findById(id: Uuid): NodeRow?
    fun findByTokenHash(tokenHash: String): NodeRow?
    fun listAll(): List<NodeRow>
    fun listByIds(ids: List<Uuid>): List<NodeRow>
    fun create(
        displayName: String,
        hostname: String,
        publicIp: String,
        privateIp: String,
        tokenHash: String,
        portRangeStart: Int,
        portRangeEnd: Int,
    ): NodeRow

    fun update(id: Uuid, displayName: String?, portRangeStart: Int?, portRangeEnd: Int?)
    fun updateStatus(id: Uuid, status: String)
    fun updateHealth(id: Uuid, health: String)
    fun updateLastSeen(id: Uuid, lastSeenAt: Instant, publicIp: String?, agentVersion: String?)
    fun updateSystemRam(id: Uuid, ramUsedMb: Int)
    fun updateSwarmActive(id: Uuid, swarmActive: Boolean)
    fun markUnreachable(id: Uuid, lastSeenAt: Instant?)
    fun markDecommissioned(id: Uuid)
    fun updateTokenHash(id: Uuid, tokenHash: String)

    fun calculateAllocatedRam(id: Uuid): Int
    fun calculateAllocatedCpu(id: Uuid): Int

    fun insertMetrics(
        nodeId: Uuid,
        cpuPercent: Double,
        ramUsedMb: Int,
        ramTotalMb: Int,
        netInBytes: Long,
        netOutBytes: Long,
        diskUsedBytes: Long,
        diskTotalBytes: Long,
        recordedAt: Instant,
    )

    fun getMetrics(nodeId: Uuid, limit: Int): List<NodeMetricsRow>
}
