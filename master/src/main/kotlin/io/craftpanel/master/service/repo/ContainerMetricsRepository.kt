package io.craftpanel.master.service.repo

import kotlinx.datetime.Instant
import kotlin.uuid.Uuid

data class ContainerMetricsRow(
    val id: Uuid,
    val serverId: Uuid,
    val recordedAt: String,
    val cpuPercent: Double,
    val ramUsedMb: Int,
    val netInBytes: Long,
    val netOutBytes: Long,
    val blockInBytes: Long,
    val blockOutBytes: Long
)

interface ContainerMetricsRepository {

    fun insertContainerMetrics(serverId: Uuid, cpuPercent: Double, ramUsedMb: Int, netInBytes: Long, netOutBytes: Long, blockInBytes: Long, blockOutBytes: Long, recordedAt: Instant)
    fun getContainerMetrics(serverId: Uuid, seconds: Int): List<ContainerMetricsRow>
    fun getContainerMetricsByRange(serverId: Uuid, from: Instant, to: Instant): List<ContainerMetricsRow>
    fun getLatestContainerMetrics(serverId: Uuid): ContainerMetricsRow?
    fun getLatestContainerMetricsForServers(serverIds: List<Uuid>): Map<Uuid, ContainerMetricsRow?>
    fun deleteContainerMetricsForServer(serverId: Uuid)
}
