package io.craftpanel.master.routes

import io.craftpanel.master.auth.ScopeType
import io.craftpanel.master.domain.*
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.time.Clock
import kotlin.uuid.Uuid

internal enum class WsEventType(val event: String) {
    SNAPSHOT("snapshot"),
    NODE_METRICS("node.metrics"),
    NODE_STATUS("node.status"),
    SERVER_METRICS("server.metrics"),
    SERVER_STATUS("server.status"),
    SERVER_PLAYERS("server.players"),
    SERVER_BACKUP_PROGRESS("server.backup.progress"),
    SERVER_BACKUP_COMPLETE("server.backup.complete"),
    ALERT_FIRED("alert.fired"),
    ALERT_RESOLVED("alert.resolved");

    override fun toString(): String = event
}

/**
 * Applies per-user visibility gates to inbound [AgentEvent]s and builds the initial dashboard
 * snapshot. Kept free of DB/socket access so it can be unit tested in isolation.
 */
class DashboardEventFilter(
    private val hasNodes: () -> Boolean,
    private val canViewServer: (Uuid, Uuid?) -> Boolean,
    private val serverNetworkId: (String) -> Uuid?,
    private val clock: Clock = Clock.System
) {

    fun toEnvelope(event: AgentEvent): WsEnvelope? = when (event) {
        is AgentEvent.NodeMetricsEvent -> {
            if (!hasNodes()) return null
            envelope(
                WsEventType.NODE_METRICS,
                NodeMetricsPayload(
                    event.nodeId, event.cpuPercent, event.ramUsedMb, event.ramTotalMb,
                    event.netInBytes, event.netOutBytes, event.diskUsedBytes, event.diskTotalBytes,
                    event.recordedAt.toString()
                )
            )
        }

        is AgentEvent.NodeStatusEvent -> {
            if (!hasNodes()) return null
            envelope(
                WsEventType.NODE_STATUS,
                NodeStatusPayload(
                    event.nodeId,
                    event.health,
                    clock.now()
                        .toString()
                )
            )
        }

        is AgentEvent.ContainerMetricsEvent -> {
            val sid = runCatching { Uuid.parse(event.serverId) }.getOrNull() ?: return null
            val netId = serverNetworkId(event.serverId)
            if (!canViewServer(sid, netId)) return null
            envelope(
                WsEventType.SERVER_METRICS,
                ServerMetricsPayload(
                    event.serverId,
                    event.cpuPercent,
                    event.ramUsedMb,
                    event.netInBytes,
                    event.netOutBytes,
                    event.blockInBytes,
                    event.blockOutBytes,
                    event.recordedAt.toString()
                )
            )
        }

        is AgentEvent.ServerStatusEvent -> {
            val sid = runCatching { Uuid.parse(event.serverId) }.getOrNull() ?: return null
            val netId = serverNetworkId(event.serverId)
            if (!canViewServer(sid, netId)) return null
            envelope(
                WsEventType.SERVER_STATUS,
                ServerStatusPayload(
                    event.serverId,
                    event.status,
                    clock.now()
                        .toString()
                )
            )
        }

        is AgentEvent.PlayerUpdateEvent -> {
            val sid = runCatching { Uuid.parse(event.serverId) }.getOrNull() ?: return null
            val netId = serverNetworkId(event.serverId)
            if (!canViewServer(sid, netId)) return null
            envelope(
                WsEventType.SERVER_PLAYERS,
                ServerPlayersPayload(
                    event.serverId,
                    event.playerCount,
                    event.playerNames,
                    event.recordedAt.toString()
                )
            )
        }

        is AgentEvent.BackupProgressEvent -> {
            val sid = runCatching { Uuid.parse(event.serverId) }.getOrNull() ?: return null
            val netId = serverNetworkId(event.serverId)
            if (!canViewServer(sid, netId)) return null
            envelope(
                WsEventType.SERVER_BACKUP_PROGRESS,
                BackupProgressPayload(
                    event.serverId,
                    event.backupId,
                    event.percentComplete,
                    clock.now()
                        .toString()
                )
            )
        }

        is AgentEvent.BackupCompleteEvent -> {
            val sid = runCatching { Uuid.parse(event.serverId) }.getOrNull() ?: return null
            val netId = serverNetworkId(event.serverId)
            if (!canViewServer(sid, netId)) return null
            val status = if (event.success) BackupStatus.COMPLETED else BackupStatus.FAILED
            envelope(
                WsEventType.SERVER_BACKUP_COMPLETE,
                BackupCompletePayload(
                    event.serverId,
                    event.backupId,
                    status,
                    event.sizeBytes,
                    if (!event.success) event.errorMessage else null,
                    event.completedAt.toString()
                )
            )
        }

        is AgentEvent.AlertFiredEvent -> {
            val isResolved = event.resolvedAt != null
            if (event.scopeType == ScopeType.NODE.name && !hasNodes()) return null
            if (event.scopeType == ScopeType.SERVER.name) {
                val sid = runCatching { Uuid.parse(event.scopeId) }.getOrNull() ?: return null
                val netId = serverNetworkId(event.scopeId)
                if (!canViewServer(sid, netId)) return null
            }
            val type = if (isResolved) WsEventType.ALERT_RESOLVED else WsEventType.ALERT_FIRED
            envelope(
                type,
                AlertPayload(
                    event.eventId,
                    event.thresholdId,
                    ScopeType.valueOf(event.scopeType),
                    event.scopeId,
                    event.metric,
                    event.message,
                    if (!isResolved) event.firedAt else null,
                    if (isResolved) event.resolvedAt else null
                )
            )
        }

        is AgentEvent.RsyncReadyEvent, is AgentEvent.RsyncProgressEvent, is AgentEvent.RsyncCompleteEvent -> null
    }

    fun snapshot(serverRows: List<ServerRow>, latestMetrics: Map<Uuid, ContainerMetricsRow?>, nodeRows: List<NodeRow>): WsEnvelope {
        val servers = serverRows.mapNotNull { row ->
            if (!canViewServer(row.id, row.networkId)) return@mapNotNull null
            val status = ServerStatus.fromDb(row.status)
            val metricsRow = if (status.isStopped) null else latestMetrics[row.id]
            ServerSnapshot(
                row.id.toString(),
                row.displayName,
                status,
                row.nodeId.toString(),
                row.networkId?.toString(),
                metricsRow?.let {
                    ServerMetricsSnapshot(
                        it.cpuPercent,
                        it.ramUsedMb,
                        it.netInBytes,
                        it.netOutBytes,
                        it.blockInBytes,
                        it.blockOutBytes,
                        it.recordedAt
                    )
                }
            )
        }
        val nodes = if (hasNodes()) {
            nodeRows.map { row ->
                NodeSnapshot(row.id.toString(), row.displayName, NodeStatus.fromDb(row.status), NodeHealth.valueOf(row.health))
            }
        } else {
            emptyList()
        }

        return envelope(WsEventType.SNAPSHOT, SnapshotPayload(servers, nodes))
    }

    private inline fun <reified T> envelope(type: WsEventType, payload: T): WsEnvelope = WsEnvelope(type.event, wsJson.encodeToJsonElement(payload))
}
