package io.craftpanel.master.service.repo

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
    val reservedRamMb: Int = 1024,
    val portRangeStart: Int,
    val portRangeEnd: Int,
    val swarmActive: Boolean,
    val agentVersion: String?,
    val lastSeenAt: String?,
    val createdAt: String,
    val updatedAt: String
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
    val diskTotalBytes: Long
)

interface NodeRepository {

    fun findById(id: Uuid): NodeRow?
    fun findByTokenHash(tokenHash: String): NodeRow?
    fun listAll(): List<NodeRow>
    fun listByIds(ids: List<Uuid>): List<NodeRow>

    fun calculateAllocatedRam(id: Uuid): Int
    fun calculateAllocatedCpu(id: Uuid): Int

    fun getMetrics(nodeId: Uuid, limit: Int): List<NodeMetricsRow>
}
