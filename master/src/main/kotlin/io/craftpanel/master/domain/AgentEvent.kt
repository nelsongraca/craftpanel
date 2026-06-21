package io.craftpanel.master.domain

import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
enum class NodeHealth { HEALTHY, DEGRADED, UNREACHABLE }

sealed class AgentEvent {
    data class ServerStatusEvent(
        val serverId: String,
        val status: ServerStatus,
    ) : AgentEvent()

    data class NodeStatusEvent(
        val nodeId: String,
        val health: NodeHealth,
    ) : AgentEvent()

    data class BackupProgressEvent(
        val serverId: String,
        val backupId: String,
        val percentComplete: Int,
    ) : AgentEvent()

    data class BackupCompleteEvent(
        val serverId: String,
        val backupId: String,
        val success: Boolean,
        val sizeBytes: Long,
        val errorMessage: String,
        val completedAt: Instant,
    ) : AgentEvent()

    data class AlertFiredEvent(
        val eventId: String,
        val thresholdId: String,
        val scopeType: String,
        val scopeId: String,
        val metric: String,
        val message: String,
        val firedAt: String,
        val resolvedAt: String?,
    ) : AgentEvent()

    data class RsyncReadyEvent(
        val migrationId: String,
        val rsyncPassword: String,
    ) : AgentEvent()

    data class RsyncProgressEvent(
        val migrationId: String,
        val isFinalPass: Boolean,
        val percentComplete: Int,
        val bytesTransferred: Long,
        val phase: String,
    ) : AgentEvent()

    data class RsyncCompleteEvent(
        val migrationId: String,
        val isFinalPass: Boolean,
        val success: Boolean,
        val errorMessage: String,
    ) : AgentEvent()

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
    ) : AgentEvent()

    data class ContainerMetricsEvent(
        val serverId: String,
        val cpuPercent: Double,
        val ramUsedMb: Int,
        val netInBytes: Long,
        val netOutBytes: Long,
        val recordedAt: Instant,
    ) : AgentEvent()

    data class PlayerUpdateEvent(
        val serverId: String,
        val playerCount: Int,
        val playerNames: List<String>,
        val recordedAt: Instant,
    ) : AgentEvent()
}
