@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.craftpanel.master.routes

import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.ScopeType
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.auth.WsTicketService
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.domain.AgentMetricEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlin.uuid.Uuid
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.websocket.DefaultWebSocketSession
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

private val wsJson = Json { ignoreUnknownKeys = true; namingStrategy = JsonNamingStrategy.SnakeCase }

private inline fun <reified T> DefaultWebSocketSession.sendWs(type: WsEventType, payload: T) {
    outgoing.trySend(Frame.Text(wsJson.encodeToString(WsEnvelope(type.event, wsJson.encodeToJsonElement(payload)))))
}

private fun DefaultWebSocketSession.sendWsRaw(envelope: WsEnvelope) {
    outgoing.trySend(Frame.Text(wsJson.encodeToString(envelope)))
}

fun Route.dashboardWsRoutes(wsTicketService: WsTicketService, agentEvents: SharedFlow<AgentEvent>, agentMetricsFlow: SharedFlow<AgentMetricEvent>) {
    // operationId: dashboardWebSocket
    // Requires: ?ticket=<ws-ticket> (from POST /api/auth/ws-ticket)
    // Emits server/node status, metrics, alerts, and player updates as JSON envelopes.
    webSocket("/api/ws") {
        val ticket = call.request.queryParameters["ticket"] ?: run {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing ticket"))
            return@webSocket
        }
        val userId = wsTicketService.consume(ticket) ?: run {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid or expired ticket"))
            return@webSocket
        }

        fun hasNodes() = PermissionResolver.hasPermission(userId, Permission.SYSTEM_NODES)

        fun canViewServer(serverId: Uuid, networkId: Uuid?): Boolean =
            PermissionResolver.hasPermission(userId, Permission.SERVER_VIEW, serverId, networkId)

        fun serverNetworkId(serverId: String): Uuid? = transaction {
            val kId = runCatching { Uuid.parse(serverId) }.getOrNull() ?: return@transaction null
            Servers.selectAll()
                .where { Servers.id eq kId }
                .firstOrNull()
                ?.get(Servers.networkId)
        }

        // ── Initial snapshot ──────────────────────────────────────────────────
        val snapshot = transaction {
            val servers = Servers.selectAll()
                .mapNotNull { row ->
                    val sid = row[Servers.id]
                    val netId = row[Servers.networkId]
                    if (!canViewServer(sid, netId)) return@mapNotNull null
                    ServerSnapshot(
                        sid.toString(),
                        row[Servers.displayName],
                        row[Servers.status],
                        row[Servers.nodeId].toString(),
                        row[Servers.networkId]?.toString(),
                    )
                }
            val nodes = if (hasNodes()) {
                Nodes.selectAll()
                    .map { row ->
                        NodeSnapshot(row[Nodes.id].toString(), row[Nodes.displayName], row[Nodes.status], row[Nodes.health])
                    }
            }
            else emptyList()

            WsEnvelope(WsEventType.SNAPSHOT.event, wsJson.encodeToJsonElement(SnapshotPayload(servers, nodes)))
        }
        sendWsRaw(snapshot)

        // ── Subscriptions ─────────────────────────────────────────────────────

        val nodeMetricsJob = launch {
            agentMetricsFlow.filterIsInstance<AgentMetricEvent.NodeMetricsEvent>()
                .collect { event ->
                    if (!hasNodes()) return@collect
                    sendWs(
                        WsEventType.NODE_METRICS, NodeMetricsPayload(
                            event.nodeId,
                            event.cpuPercent,
                            event.ramUsedMb,
                            event.ramTotalMb,
                            event.netInBytes,
                            event.netOutBytes,
                            event.diskUsedBytes,
                            event.diskTotalBytes,
                            event.recordedAt.toString(),
                        )
                    )
                }
        }

        val nodeStatusJob = launch {
            agentEvents.filterIsInstance<AgentEvent.NodeStatusEvent>()
                .collect { event ->
                    if (!hasNodes()) return@collect
                    sendWs(
                        WsEventType.NODE_STATUS,
                        NodeStatusPayload(
                            event.nodeId,
                            event.health.name,
                            Clock.System.now()
                                .toString()
                        )
                    )
                }
        }

        val serverMetricsJob = launch {
            agentMetricsFlow.filterIsInstance<AgentMetricEvent.ContainerMetricsEvent>()
                .collect { event ->
                    val sid = runCatching { Uuid.parse(event.serverId) }.getOrNull() ?: return@collect
                    val netId = serverNetworkId(event.serverId)
                    if (!canViewServer(sid, netId)) return@collect
                    sendWs(
                        WsEventType.SERVER_METRICS, ServerMetricsPayload(
                            event.serverId,
                            event.cpuPercent,
                            event.ramUsedMb,
                            event.netInBytes,
                            event.netOutBytes,
                            event.recordedAt.toString(),
                        )
                    )
                }
        }

        val serverStatusJob = launch {
            agentEvents.filterIsInstance<AgentEvent.ServerStatusEvent>()
                .collect { event ->
                    val sid = runCatching { Uuid.parse(event.serverId) }.getOrNull() ?: return@collect
                    val netId = serverNetworkId(event.serverId)
                    if (!canViewServer(sid, netId)) return@collect
                    sendWs(
                        WsEventType.SERVER_STATUS,
                        ServerStatusPayload(
                            event.serverId,
                            event.status.name,
                            Clock.System.now()
                                .toString()
                        )
                    )
                }
        }

        val playerJob = launch {
            agentMetricsFlow.filterIsInstance<AgentMetricEvent.PlayerUpdateEvent>()
                .collect { event ->
                    val sid = runCatching { Uuid.parse(event.serverId) }.getOrNull() ?: return@collect
                    val netId = serverNetworkId(event.serverId)
                    if (!canViewServer(sid, netId)) return@collect
                    sendWs(
                        WsEventType.SERVER_PLAYERS, ServerPlayersPayload(
                            event.serverId,
                            event.playerCount,
                            event.playerNames,
                            event.recordedAt.toString(),
                        )
                    )
                }
        }

        val backupProgressJob = launch {
            agentEvents.filterIsInstance<AgentEvent.BackupProgressEvent>()
                .collect { event ->
                    val sid = runCatching { Uuid.parse(event.serverId) }.getOrNull() ?: return@collect
                    val netId = serverNetworkId(event.serverId)
                    if (!canViewServer(sid, netId)) return@collect
                    sendWs(
                        WsEventType.SERVER_BACKUP_PROGRESS,
                        BackupProgressPayload(
                            event.serverId,
                            event.backupId,
                            event.percentComplete,
                            Clock.System.now()
                                .toString()
                        )
                    )
                }
        }

        val backupCompleteJob = launch {
            agentEvents.filterIsInstance<AgentEvent.BackupCompleteEvent>()
                .collect { event ->
                    val sid = runCatching { Uuid.parse(event.serverId) }.getOrNull() ?: return@collect
                    val netId = serverNetworkId(event.serverId)
                    if (!canViewServer(sid, netId)) return@collect
                    val status = if (event.success) "COMPLETED" else "FAILED"
                    sendWs(
                        WsEventType.SERVER_BACKUP_COMPLETE, BackupCompletePayload(
                            event.serverId,
                            event.backupId,
                            status,
                            event.sizeBytes,
                            if (!event.success) event.errorMessage else null,
                            event.completedAt.toString(),
                        )
                    )
                }
        }

        val alertJob = launch {
            agentEvents.filterIsInstance<AgentEvent.AlertFiredEvent>()
                .collect { alert ->
                    val isResolved = alert.resolvedAt != null
                    if (alert.scopeType == ScopeType.NODE.name && !hasNodes()) return@collect
                    if (alert.scopeType == ScopeType.SERVER.name) {
                        val sid = runCatching { Uuid.parse(alert.scopeId) }.getOrNull() ?: return@collect
                        val netId = serverNetworkId(alert.scopeId)
                        if (!canViewServer(sid, netId)) return@collect
                    }
                    val type = if (isResolved) WsEventType.ALERT_RESOLVED else WsEventType.ALERT_FIRED
                    sendWs(
                        type, AlertPayload(
                            alert.eventId,
                            alert.thresholdId,
                            alert.scopeType,
                            alert.scopeId,
                            alert.metric,
                            alert.message,
                            if (!isResolved) alert.firedAt else null,
                            if (isResolved) alert.resolvedAt else null,
                        )
                    )
                }
        }

        // ── 5-min permission revalidation ─────────────────────────────────────
        val revalidationJob = launch {
            while (true) {
                delay(5.minutes)
                // Touch permission store — connections are pruned if user deactivated
                runCatching { PermissionResolver.hasPermission(userId, Permission.SERVER_VIEW) }
            }
        }

        try {
            incoming.consumeEach { }
        }
        finally {
            nodeMetricsJob.cancel()
            nodeStatusJob.cancel()
            serverMetricsJob.cancel()
            serverStatusJob.cancel()
            playerJob.cancel()
            backupProgressJob.cancel()
            backupCompleteJob.cancel()
            alertJob.cancel()
            revalidationJob.cancel()
        }
    }
}
