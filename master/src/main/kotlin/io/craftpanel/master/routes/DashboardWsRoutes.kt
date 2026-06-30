@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.craftpanel.master.routes

import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.ScopeType
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.domain.NodeHealth
import io.craftpanel.master.domain.NodeStatus
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.domain.BackupStatus
import io.craftpanel.master.auth.WsTicketService
import io.craftpanel.master.database.schema.ContainerMetrics
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.AgentEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlin.reflect.KClass
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
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

private enum class WsEventType(val event: String) {
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

internal val wsJson = Json { ignoreUnknownKeys = true; namingStrategy = JsonNamingStrategy.SnakeCase }

private inline fun <reified T> DefaultWebSocketSession.sendWs(type: WsEventType, payload: T) {
    outgoing.trySend(Frame.Text(wsJson.encodeToString(WsEnvelope(type.event, wsJson.encodeToJsonElement(payload)))))
}

private fun DefaultWebSocketSession.sendWsRaw(envelope: WsEnvelope) {
    outgoing.trySend(Frame.Text(wsJson.encodeToString(envelope)))
}

fun Route.dashboardWsRoutes(wsTicketService: WsTicketService, agentEvents: SharedFlow<AgentEvent>) {
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
            val serverRows = Servers.selectAll()
                .toList()
            val latestMetrics = serverRows.associate { row ->
                val sid = row[Servers.id]
                val metricsRow = ContainerMetrics.selectAll()
                    .where { ContainerMetrics.serverId eq sid }
                    .orderBy(ContainerMetrics.recordedAt, SortOrder.DESC)
                    .limit(1)
                    .firstOrNull()
                sid to metricsRow?.let {
                    ServerMetricsSnapshot(
                        it[ContainerMetrics.cpuPercent],
                        it[ContainerMetrics.ramUsedMb],
                        it[ContainerMetrics.netInBytes],
                        it[ContainerMetrics.netOutBytes],
                        it[ContainerMetrics.blockInBytes],
                        it[ContainerMetrics.blockOutBytes],
                        it[ContainerMetrics.recordedAt].toString(),
                    )
                }
            }
            val servers = serverRows.mapNotNull { row ->
                val sid = row[Servers.id]
                val netId = row[Servers.networkId]
                if (!canViewServer(sid, netId)) return@mapNotNull null
                ServerSnapshot(
                    sid.toString(),
                    row[Servers.displayName],
                    ServerStatus.fromDb(row[Servers.status]),
                    row[Servers.nodeId].toString(),
                    row[Servers.networkId]?.toString(),
                    latestMetrics[sid],
                )
            }
            val nodes = if (hasNodes()) {
                Nodes.selectAll()
                    .map { row ->
                        NodeSnapshot(row[Nodes.id].toString(), row[Nodes.displayName], NodeStatus.fromDb(row[Nodes.status]), NodeHealth.valueOf(row[Nodes.health]))
                    }
            }
            else emptyList()

            WsEnvelope(WsEventType.SNAPSHOT.event, wsJson.encodeToJsonElement(SnapshotPayload(servers, nodes)))
        }
        sendWsRaw(snapshot)

        // ── Subscriptions ─────────────────────────────────────────────────────

        val handlers: Map<KClass<out AgentEvent>, suspend (AgentEvent) -> Unit> = mapOf(
            AgentEvent.NodeMetricsEvent::class to { event ->
                val e = event as AgentEvent.NodeMetricsEvent
                if (!hasNodes()) return@to
                sendWs(
                    WsEventType.NODE_METRICS, NodeMetricsPayload(
                        e.nodeId, e.cpuPercent, e.ramUsedMb, e.ramTotalMb,
                        e.netInBytes, e.netOutBytes, e.diskUsedBytes, e.diskTotalBytes,
                        e.recordedAt.toString(),
                    )
                )
            },
            AgentEvent.NodeStatusEvent::class to { event ->
                val e = event as AgentEvent.NodeStatusEvent
                if (!hasNodes()) return@to
                sendWs(
                    WsEventType.NODE_STATUS, NodeStatusPayload(
                        e.nodeId,
                        e.health,
                        Clock.System.now()
                            .toString(),
                    )
                )
            },
            AgentEvent.ContainerMetricsEvent::class to { event ->
                val e = event as AgentEvent.ContainerMetricsEvent
                val sid = runCatching { Uuid.parse(e.serverId) }.getOrNull() ?: return@to
                val netId = serverNetworkId(e.serverId)
                if (!canViewServer(sid, netId)) return@to
                sendWs(
                    WsEventType.SERVER_METRICS, ServerMetricsPayload(
                        e.serverId, e.cpuPercent, e.ramUsedMb,
                        e.netInBytes, e.netOutBytes,
                        e.blockInBytes, e.blockOutBytes, e.recordedAt.toString(),
                    )
                )
            },
            AgentEvent.ServerStatusEvent::class to { event ->
                val e = event as AgentEvent.ServerStatusEvent
                val sid = runCatching { Uuid.parse(e.serverId) }.getOrNull() ?: return@to
                val netId = serverNetworkId(e.serverId)
                if (!canViewServer(sid, netId)) return@to
                sendWs(
                    WsEventType.SERVER_STATUS, ServerStatusPayload(
                        e.serverId,
                        e.status,
                        Clock.System.now()
                            .toString(),
                    )
                )
            },
            AgentEvent.PlayerUpdateEvent::class to { event ->
                val e = event as AgentEvent.PlayerUpdateEvent
                val sid = runCatching { Uuid.parse(e.serverId) }.getOrNull() ?: return@to
                val netId = serverNetworkId(e.serverId)
                if (!canViewServer(sid, netId)) return@to
                sendWs(
                    WsEventType.SERVER_PLAYERS, ServerPlayersPayload(
                        e.serverId, e.playerCount, e.playerNames, e.recordedAt.toString(),
                    )
                )
            },
            AgentEvent.BackupProgressEvent::class to { event ->
                val e = event as AgentEvent.BackupProgressEvent
                val sid = runCatching { Uuid.parse(e.serverId) }.getOrNull() ?: return@to
                val netId = serverNetworkId(e.serverId)
                if (!canViewServer(sid, netId)) return@to
                sendWs(
                    WsEventType.SERVER_BACKUP_PROGRESS, BackupProgressPayload(
                        e.serverId,
                        e.backupId,
                        e.percentComplete,
                        Clock.System.now()
                            .toString(),
                    )
                )
            },
            AgentEvent.BackupCompleteEvent::class to { event ->
                val e = event as AgentEvent.BackupCompleteEvent
                val sid = runCatching { Uuid.parse(e.serverId) }.getOrNull() ?: return@to
                val netId = serverNetworkId(e.serverId)
                if (!canViewServer(sid, netId)) return@to
                val status = if (e.success) BackupStatus.COMPLETED else BackupStatus.FAILED
                sendWs(
                    WsEventType.SERVER_BACKUP_COMPLETE, BackupCompletePayload(
                        e.serverId, e.backupId, status,
                        e.sizeBytes, if (!e.success) e.errorMessage else null,
                        e.completedAt.toString(),
                    )
                )
            },
            AgentEvent.AlertFiredEvent::class to { event ->
                val alert = event as AgentEvent.AlertFiredEvent
                val isResolved = alert.resolvedAt != null
                if (alert.scopeType == ScopeType.NODE.name && !hasNodes()) return@to
                if (alert.scopeType == ScopeType.SERVER.name) {
                    val sid = runCatching { Uuid.parse(alert.scopeId) }.getOrNull() ?: return@to
                    val netId = serverNetworkId(alert.scopeId)
                    if (!canViewServer(sid, netId)) return@to
                }
                val type = if (isResolved) WsEventType.ALERT_RESOLVED else WsEventType.ALERT_FIRED
                sendWs(
                    type, AlertPayload(
                        alert.eventId, alert.thresholdId, ScopeType.valueOf(alert.scopeType), alert.scopeId,
                        alert.metric, alert.message,
                        if (!isResolved) alert.firedAt else null,
                        if (isResolved) alert.resolvedAt else null,
                    )
                )
            },
        )

        val subscriptionsJob = launch {
            handlers.forEach { (cls, handler) ->
                launch {
                    agentEvents.filterIsInstance(cls)
                        .collect { handler(it) }
                }
            }
        }

        // ── 5-min permission revalidation ─────────────────────────────────────
        val revalidationJob = launch {
            while (true) {
                delay(5.minutes)
                runCatching { PermissionResolver.hasPermission(userId, Permission.SERVER_VIEW) }
            }
        }

        try {
            incoming.consumeEach { }
        }
        finally {
            subscriptionsJob.cancel()
            revalidationJob.cancel()
        }
    }
}
