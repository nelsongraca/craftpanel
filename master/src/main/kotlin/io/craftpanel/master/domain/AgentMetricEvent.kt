package io.craftpanel.master.domain

import kotlin.time.Instant

sealed class AgentMetricEvent {
    data class NodeMetricsEvent(
        val nodeId: String,
        val cpuPercent: Double,
        val ramUsedMb: Int,
        val ramTotalMb: Int,
        val netInBytes: Long,
        val netOutBytes: Long,
        val diskUsedBytes: Long,
        val diskTotalBytes: Long,
        val recordedAt: Instant,
    ) : AgentMetricEvent()

    data class ContainerMetricsEvent(
        val serverId: String,
        val cpuPercent: Double,
        val ramUsedMb: Int,
        val netInBytes: Long,
        val netOutBytes: Long,
        val recordedAt: Instant,
    ) : AgentMetricEvent()

    data class PlayerUpdateEvent(
        val serverId: String,
        val playerCount: Int,
        val playerNames: List<String>,
        val recordedAt: Instant,
    ) : AgentMetricEvent()
}
