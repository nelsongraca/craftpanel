package io.craftpanel.master.service.repo

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
    val blockOutBytes: Long,
    val heapUsedBytes: Long? = null,
    val heapMaxBytes: Long? = null,
    val nonHeapUsedBytes: Long? = null
)

interface ContainerMetricsRepository {

    fun getContainerMetrics(serverId: Uuid, seconds: Int): List<ContainerMetricsRow>
    fun getContainerMetricsByRange(serverId: Uuid, from: kotlin.time.Instant, to: kotlin.time.Instant): List<ContainerMetricsRow>
    fun getLatestContainerMetrics(serverId: Uuid): ContainerMetricsRow?
    fun getLatestContainerMetricsForServers(serverIds: List<Uuid>): Map<Uuid, ContainerMetricsRow?>

    /** Deletes samples recorded strictly before [cutoff]. Returns the number of rows removed. */
    fun deleteOlderThan(cutoff: kotlin.time.Instant): Int
}
