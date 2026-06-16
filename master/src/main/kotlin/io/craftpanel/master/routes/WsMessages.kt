package io.craftpanel.master.routes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class WsEnvelope(val type: String, val payload: JsonElement)

@Serializable
data class ServerSnapshot(
    val id: String,
    val displayName: String,
    val status: String,
    val nodeId: String,
    val networkId: String? = null,
)

@Serializable
data class NodeSnapshot(val id: String, val displayName: String, val status: String)

@Serializable
data class SnapshotPayload(val servers: List<ServerSnapshot>, val nodes: List<NodeSnapshot>)

@Serializable
data class NodeMetricsPayload(
    val nodeId: String,
    val cpuPercent: Double,
    val ramUsedMb: Int,
    val ramTotalMb: Int,
    val netInBytes: Long,
    val netOutBytes: Long,
    val diskUsedBytes: Long,
    val diskTotalBytes: Long,
    val recordedAt: String,
)

@Serializable
data class NodeStatusPayload(val nodeId: String, val status: String, val recordedAt: String)

@Serializable
data class ServerMetricsPayload(
    val serverId: String,
    val cpuPercent: Double,
    val ramUsedMb: Int,
    val netInBytes: Long,
    val netOutBytes: Long,
    val recordedAt: String,
)

@Serializable
data class ServerStatusPayload(
    val serverId: String,
    val status: String,
    val recordedAt: String,
)

@Serializable
data class ServerPlayersPayload(
    val serverId: String,
    val playerCount: Int,
    val playerList: List<String>,
    val recordedAt: String,
)

@Serializable
data class BackupProgressPayload(
    val serverId: String,
    val backupId: String,
    val percentComplete: Int,
    val recordedAt: String,
)

@Serializable
data class BackupCompletePayload(
    val serverId: String,
    val backupId: String,
    val status: String,
    val sizeBytes: Long,
    val errorMessage: String? = null,
    val completedAt: String,
)

@Serializable
data class AlertPayload(
    val eventId: String,
    val thresholdId: String,
    val scopeType: String,
    val scopeId: String,
    val metric: String,
    val message: String,
    val firedAt: String? = null,
    val resolvedAt: String? = null,
)

@Serializable
sealed class ConsoleEvent {

    @Serializable
    @SerialName("console.ready")
    data class Ready(val serverId: String) : ConsoleEvent()

    @Serializable
    @SerialName("console.output")
    data class Output(val data: String) : ConsoleEvent()

    @Serializable
    @SerialName("console.disconnected")
    data class Disconnected(val serverId: String, val reason: String) : ConsoleEvent()
}

@Serializable
sealed class ConsoleInEvent {

    @Serializable
    @SerialName("console.input")
    data class Input(val data: String = "") : ConsoleInEvent()
}
