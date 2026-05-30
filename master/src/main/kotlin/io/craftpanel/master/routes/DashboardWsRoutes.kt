@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.craftpanel.master.routes

import io.craftpanel.master.auth.JWT_AUTH
import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.ScopeType
import com.craftpanel.agent.v1.ServerStatusUpdate
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.auth.WsTicketService
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.grpc.ControlServiceImpl
import io.craftpanel.master.util.toKotlinUuid
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.websocket.DefaultWebSocketSession
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val wsJson = Json { ignoreUnknownKeys = true; namingStrategy = JsonNamingStrategy.SnakeCase }

private inline fun <reified T> DefaultWebSocketSession.sendWs(type: WsEventType, payload: T) {
    outgoing.trySend(Frame.Text(wsJson.encodeToString(WsEnvelope(type.event, wsJson.encodeToJsonElement(payload)))))
}

private fun DefaultWebSocketSession.sendWsRaw(envelope: WsEnvelope) {
    outgoing.trySend(Frame.Text(wsJson.encodeToString(envelope)))
}

fun Route.dashboardWsRoutes(wsTicketService: WsTicketService, controlService: ControlServiceImpl) {
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

        fun canViewServer(serverId: UUID, networkId: UUID?): Boolean =
            PermissionResolver.hasPermission(userId, Permission.SERVER_VIEW, serverId, networkId)

        fun serverNetworkId(serverId: String): UUID? = transaction {
            val kId = runCatching { UUID.fromString(serverId) }.getOrNull() ?: return@transaction null
            Servers.selectAll()
                .where { Servers.id eq kId.toKotlinUuid() }
                .firstOrNull()
                ?.get(Servers.networkId)
                ?.let { UUID.fromString(it.toString()) }
        }

        // ── Initial snapshot ──────────────────────────────────────────────────
        val snapshot = transaction {
            val servers = Servers.selectAll()
                .mapNotNull { row ->
                    val sid = UUID.fromString(row[Servers.id].toString())
                    val netId = row[Servers.networkId]?.let { UUID.fromString(it.toString()) }
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
                        NodeSnapshot(row[Nodes.id].toString(), row[Nodes.displayName], row[Nodes.status])
                    }
            }
            else emptyList()

            WsEnvelope(WsEventType.SNAPSHOT.event, wsJson.encodeToJsonElement(SnapshotPayload(servers, nodes)))
        }
        sendWsRaw(snapshot)

        // ── Subscriptions ─────────────────────────────────────────────────────

        val nodeMetricsJob = launch {
            controlService.nodeMetricsFlow.collect { (nodeId, metrics) ->
                if (!hasNodes()) return@collect
                sendWs(
                    WsEventType.NODE_METRICS, NodeMetricsPayload(
                        nodeId,
                        metrics.cpuPercent,
                        metrics.ramUsedMb,
                        metrics.ramTotalMb,
                        metrics.netInBytes,
                        metrics.netOutBytes,
                        metrics.diskUsedBytes,
                        metrics.diskTotalBytes,
                        if (metrics.hasRecordedAt())
                            Instant.fromEpochSeconds(metrics.recordedAt.seconds, metrics.recordedAt.nanos.toLong())
                                .toString()
                        else Clock.System.now()
                            .toString(),
                    )
                )
            }
        }

        val nodeStatusJob = launch {
            controlService.nodeStatusFlow.collect { (nodeId, status) ->
                if (!hasNodes()) return@collect
                sendWs(
                    WsEventType.NODE_STATUS,
                    NodeStatusPayload(
                        nodeId,
                        status,
                        Clock.System.now()
                            .toString()
                    )
                )
            }
        }

        val serverMetricsJob = launch {
            controlService.containerMetricsFlow.collect { (serverId, metrics) ->
                val sid = runCatching { UUID.fromString(serverId) }.getOrNull() ?: return@collect
                val netId = serverNetworkId(serverId)
                if (!canViewServer(sid, netId)) return@collect
                sendWs(
                    WsEventType.SERVER_METRICS, ServerMetricsPayload(
                        serverId,
                        metrics.cpuPercent,
                        metrics.ramUsedMb,
                        metrics.netInBytes,
                        metrics.netOutBytes,
                        if (metrics.hasRecordedAt())
                            Instant.fromEpochSeconds(metrics.recordedAt.seconds, metrics.recordedAt.nanos.toLong())
                                .toString()
                        else Clock.System.now()
                            .toString(),
                    )
                )
            }
        }

        val serverStatusJob = launch {
            controlService.serverStatusFlow.collect { (serverId, update) ->
                val sid = runCatching { UUID.fromString(serverId) }.getOrNull() ?: return@collect
                val netId = serverNetworkId(serverId)
                if (!canViewServer(sid, netId)) return@collect
                val statusStr = when (update.status) {
                    ServerStatusUpdate.ServerStatus.HEALTHY -> "HEALTHY"
                    ServerStatusUpdate.ServerStatus.UNHEALTHY -> "UNHEALTHY"
                    ServerStatusUpdate.ServerStatus.STARTING -> "STARTING"
                    ServerStatusUpdate.ServerStatus.STOPPED -> "STOPPED"
                    else -> return@collect
                }
                sendWs(
                    WsEventType.SERVER_STATUS,
                    ServerStatusPayload(
                        serverId,
                        statusStr,
                        update.containerId,
                        Clock.System.now()
                            .toString()
                    )
                )
            }
        }

        val playerJob = launch {
            controlService.playerUpdateFlow.collect { (serverId, update) ->
                val sid = runCatching { UUID.fromString(serverId) }.getOrNull() ?: return@collect
                val netId = serverNetworkId(serverId)
                if (!canViewServer(sid, netId)) return@collect
                sendWs(
                    WsEventType.SERVER_PLAYERS, ServerPlayersPayload(
                        serverId,
                        update.playerCount,
                        update.playerNamesList,
                        if (update.hasRecordedAt())
                            Instant.fromEpochSeconds(update.recordedAt.seconds, update.recordedAt.nanos.toLong())
                                .toString()
                        else Clock.System.now()
                            .toString(),
                    )
                )
            }
        }

        val backupProgressJob = launch {
            controlService.backupProgressFlow.collect { update ->
                val sid = runCatching { UUID.fromString(update.serverId) }.getOrNull() ?: return@collect
                val netId = serverNetworkId(update.serverId)
                if (!canViewServer(sid, netId)) return@collect
                sendWs(
                    WsEventType.SERVER_BACKUP_PROGRESS,
                    BackupProgressPayload(
                        update.serverId,
                        update.backupId,
                        update.percentComplete,
                        Clock.System.now()
                            .toString()
                    )
                )
            }
        }

        val backupCompleteJob = launch {
            controlService.backupCompleteFlow.collect { update ->
                val sid = runCatching { UUID.fromString(update.serverId) }.getOrNull() ?: return@collect
                val netId = serverNetworkId(update.serverId)
                if (!canViewServer(sid, netId)) return@collect
                val status = if (update.success) "COMPLETED" else "FAILED"
                sendWs(
                    WsEventType.SERVER_BACKUP_COMPLETE, BackupCompletePayload(
                        update.serverId,
                        update.backupId,
                        status,
                        update.sizeBytes,
                        if (!update.success) update.errorMessage else null,
                        if (update.hasCompletedAt())
                            Instant.fromEpochSeconds(update.completedAt.seconds, update.completedAt.nanos.toLong())
                                .toString()
                        else Clock.System.now()
                            .toString(),
                    )
                )
            }
        }

        val alertJob = launch {
            controlService.alertEventFlow.collect { alert ->
                val isResolved = alert.resolvedAt != null
                if (alert.scopeType == ScopeType.NODE.name && !hasNodes()) return@collect
                if (alert.scopeType == ScopeType.SERVER.name) {
                    val sid = runCatching { UUID.fromString(alert.scopeId) }.getOrNull() ?: return@collect
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
