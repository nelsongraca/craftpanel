package io.craftpanel.master.routes

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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.time.Duration.Companion.minutes

private val wsJson = Json { ignoreUnknownKeys = true }

fun Route.dashboardWsRoutes(wsTicketService: WsTicketService, controlService: ControlServiceImpl) {
    val log = LoggerFactory.getLogger("io.craftpanel.master.routes.DashboardWsRoutes")

    webSocket("/api/ws") {
        val ticket = call.request.queryParameters["ticket"] ?: run {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing ticket"))
            return@webSocket
        }
        val userId = wsTicketService.consume(ticket) ?: run {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid or expired ticket"))
            return@webSocket
        }

        fun hasNodes() = PermissionResolver.hasPermission(userId, "system.nodes")

        fun canViewServer(serverId: UUID, networkId: UUID?): Boolean =
            PermissionResolver.hasPermission(userId, "server.view", serverId, networkId)

        fun serverNetworkId(serverId: String): UUID? = transaction {
            val kId = runCatching { UUID.fromString(serverId) }.getOrNull() ?: return@transaction null
            Servers.selectAll()
                .where { Servers.id eq kId.toKotlinUuid() }
                .firstOrNull()
                ?.get(Servers.networkId)
                ?.let { UUID.fromString(it.toString()) }
        }

        fun send(obj: JsonObject) {
            outgoing.trySend(Frame.Text(wsJson.encodeToString(obj)))
        }

        fun envelope(type: String, payload: JsonObject): JsonObject = buildJsonObject {
            put("type", type)
            put("payload", payload)
        }

        // ── Initial snapshot ──────────────────────────────────────────────────
        val snapshot = transaction {
            val servers = Servers.selectAll()
                .mapNotNull { row ->
                    val sid = UUID.fromString(row[Servers.id].toString())
                    val netId = row[Servers.networkId]?.let { UUID.fromString(it.toString()) }
                    if (!canViewServer(sid, netId)) return@mapNotNull null
                    buildJsonObject {
                        put("id", sid.toString())
                        put("display_name", row[Servers.displayName])
                        put("status", row[Servers.status])
                        put("node_id", row[Servers.nodeId].toString())
                        row[Servers.networkId]?.let { put("network_id", it.toString()) }
                    }
                }
            val nodes = if (hasNodes()) {
                Nodes.selectAll()
                    .map { row ->
                        buildJsonObject {
                            put("id", row[Nodes.id].toString())
                            put("display_name", row[Nodes.displayName])
                            put("status", row[Nodes.status])
                        }
                    }
            }
            else emptyList()

            buildJsonObject {
                put("type", "snapshot")
                put("payload", buildJsonObject {
                    put("servers", buildJsonArray { servers.forEach { add(it) } })
                    put("nodes", buildJsonArray { nodes.forEach { add(it) } })
                })
            }
        }
        send(snapshot)

        // ── Subscriptions ─────────────────────────────────────────────────────

        val nodeMetricsJob = launch {
            controlService.nodeMetricsFlow.collect { (nodeId, metrics) ->
                if (!hasNodes()) return@collect
                send(envelope("node.metrics", buildJsonObject {
                    put("node_id", nodeId)
                    put("cpu_percent", metrics.cpuPercent)
                    put("ram_used_mb", metrics.ramUsedMb)
                    put("ram_total_mb", metrics.ramTotalMb)
                    put("net_in_bytes", metrics.netInBytes)
                    put("net_out_bytes", metrics.netOutBytes)
                    put("disk_used_bytes", metrics.diskUsedBytes)
                    put("disk_total_bytes", metrics.diskTotalBytes)
                    put(
                        "recorded_at", if (metrics.hasRecordedAt())
                            kotlinx.datetime.Instant.fromEpochSeconds(metrics.recordedAt.seconds, metrics.recordedAt.nanos.toLong())
                                .toString()
                        else kotlinx.datetime.Clock.System.now()
                            .toString()
                    )
                }))
            }
        }

        val nodeStatusJob = launch {
            controlService.nodeStatusFlow.collect { (nodeId, status) ->
                if (!hasNodes()) return@collect
                send(envelope("node.status", buildJsonObject {
                    put("node_id", nodeId)
                    put("status", status)
                    put("recorded_at",
                        kotlinx.datetime.Clock.System.now()
                            .toString()
                    )
                }))
            }
        }

        val serverMetricsJob = launch {
            controlService.containerMetricsFlow.collect { (serverId, metrics) ->
                val sid = runCatching { UUID.fromString(serverId) }.getOrNull() ?: return@collect
                val netId = serverNetworkId(serverId)
                if (!canViewServer(sid, netId)) return@collect
                send(envelope("server.metrics", buildJsonObject {
                    put("server_id", serverId)
                    put("cpu_percent", metrics.cpuPercent)
                    put("ram_used_mb", metrics.ramUsedMb)
                    put("net_in_bytes", metrics.netInBytes)
                    put("net_out_bytes", metrics.netOutBytes)
                    put(
                        "recorded_at", if (metrics.hasRecordedAt())
                            kotlinx.datetime.Instant.fromEpochSeconds(metrics.recordedAt.seconds, metrics.recordedAt.nanos.toLong())
                                .toString()
                        else kotlinx.datetime.Clock.System.now()
                            .toString()
                    )
                }))
            }
        }

        val serverStatusJob = launch {
            controlService.serverStatusFlow.collect { (serverId, update) ->
                val sid = runCatching { UUID.fromString(serverId) }.getOrNull() ?: return@collect
                val netId = serverNetworkId(serverId)
                if (!canViewServer(sid, netId)) return@collect
                val statusStr = when (update.status) {
                    ServerStatusUpdate.ServerStatus.HEALTHY   -> "HEALTHY"
                    ServerStatusUpdate.ServerStatus.UNHEALTHY -> "UNHEALTHY"
                    ServerStatusUpdate.ServerStatus.STARTING  -> "STARTING"
                    ServerStatusUpdate.ServerStatus.STOPPED   -> "STOPPED"
                    else                                      -> return@collect
                }
                send(envelope("server.status", buildJsonObject {
                    put("server_id", serverId)
                    put("status", statusStr)
                    put("container_id", update.containerId)
                    put("recorded_at",
                        kotlinx.datetime.Clock.System.now()
                            .toString()
                    )
                }))
            }
        }

        val playerJob = launch {
            controlService.playerUpdateFlow.collect { (serverId, update) ->
                val sid = runCatching { UUID.fromString(serverId) }.getOrNull() ?: return@collect
                val netId = serverNetworkId(serverId)
                if (!canViewServer(sid, netId)) return@collect
                send(envelope("server.players", buildJsonObject {
                    put("server_id", serverId)
                    put("player_count", update.playerCount)
                    put("player_list", buildJsonArray { update.playerNamesList.forEach { add(JsonPrimitive(it)) } })
                    put(
                        "recorded_at", if (update.hasRecordedAt())
                            kotlinx.datetime.Instant.fromEpochSeconds(update.recordedAt.seconds, update.recordedAt.nanos.toLong())
                                .toString()
                        else kotlinx.datetime.Clock.System.now()
                            .toString()
                    )
                }))
            }
        }

        val backupProgressJob = launch {
            controlService.backupProgressFlow.collect { update ->
                val sid = runCatching { UUID.fromString(update.serverId) }.getOrNull() ?: return@collect
                val netId = serverNetworkId(update.serverId)
                if (!canViewServer(sid, netId)) return@collect
                send(envelope("server.backup.progress", buildJsonObject {
                    put("server_id", update.serverId)
                    put("backup_id", update.backupId)
                    put("percent_complete", update.percentComplete)
                    put("recorded_at",
                        kotlinx.datetime.Clock.System.now()
                            .toString()
                    )
                }))
            }
        }

        val backupCompleteJob = launch {
            controlService.backupCompleteFlow.collect { update ->
                val sid = runCatching { UUID.fromString(update.serverId) }.getOrNull() ?: return@collect
                val netId = serverNetworkId(update.serverId)
                if (!canViewServer(sid, netId)) return@collect
                val status = if (update.success) "COMPLETED" else "FAILED"
                send(envelope("server.backup.complete", buildJsonObject {
                    put("server_id", update.serverId)
                    put("backup_id", update.backupId)
                    put("status", status)
                    put("size_bytes", update.sizeBytes)
                    if (!update.success) put("error_message", update.errorMessage)
                    put(
                        "completed_at", if (update.hasCompletedAt())
                            kotlinx.datetime.Instant.fromEpochSeconds(update.completedAt.seconds, update.completedAt.nanos.toLong())
                                .toString()
                        else kotlinx.datetime.Clock.System.now()
                            .toString()
                    )
                }))
            }
        }

        val alertJob = launch {
            controlService.alertEventFlow.collect { alert ->
                val isResolved = alert.resolvedAt != null
                if (alert.scopeType == "NODE" && !hasNodes()) return@collect
                if (alert.scopeType == "SERVER") {
                    val sid = runCatching { UUID.fromString(alert.scopeId) }.getOrNull() ?: return@collect
                    val netId = serverNetworkId(alert.scopeId)
                    if (!canViewServer(sid, netId)) return@collect
                }
                val type = if (isResolved) "alert.resolved" else "alert.fired"
                send(envelope(type, buildJsonObject {
                    put("event_id", alert.eventId)
                    put("threshold_id", alert.thresholdId)
                    put("scope_type", alert.scopeType)
                    put("scope_id", alert.scopeId)
                    put("metric", alert.metric)
                    put("message", alert.message)
                    if (isResolved) {
                        put("resolved_at", alert.resolvedAt!!)
                    }
                    else {
                        put("fired_at", alert.firedAt)
                    }
                }))
            }
        }

        // ── 5-min permission revalidation ─────────────────────────────────────
        val revalidationJob = launch {
            while (true) {
                kotlinx.coroutines.delay(5.minutes)
                // Touch permission store — connections are pruned if user deactivated
                runCatching { PermissionResolver.hasPermission(userId, "server.view") }
            }
        }

        try {
            for (frame in incoming) { /* server-push only — ignore client frames */
            }
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
