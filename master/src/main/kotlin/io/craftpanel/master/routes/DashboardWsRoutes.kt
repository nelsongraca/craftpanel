@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.craftpanel.master.routes

import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.auth.WsTicketService
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.service.repo.NodeRepository
import io.craftpanel.master.service.repo.ServerRepository
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.websocket.DefaultWebSocketSession
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

internal val wsJson = Json {
    ignoreUnknownKeys = true
    namingStrategy = JsonNamingStrategy.SnakeCase
}

private fun DefaultWebSocketSession.sendWsRaw(envelope: WsEnvelope) {
    outgoing.trySend(Frame.Text(wsJson.encodeToString(envelope)))
}

fun Route.dashboardWsRoutes(wsTicketService: WsTicketService, agentEvents: SharedFlow<AgentEvent>, serverRepository: ServerRepository, nodeRepository: NodeRepository) {
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

        val filter = DashboardEventFilter(
            hasNodes = { PermissionResolver.hasPermission(userId, Permission.SYSTEM_NODES) },
            canViewServer = { serverId, networkId -> PermissionResolver.hasPermission(userId, Permission.SERVER_VIEW, serverId, networkId) },
            serverNetworkId = { serverId ->
                val kId = runCatching { Uuid.parse(serverId) }.getOrNull()
                kId?.let { serverRepository.findById(it)?.networkId }
            }
        )

        // ── Initial snapshot ──────────────────────────────────────────────────
        val serverRows = serverRepository.listAll()
        val latestMetrics = serverRepository.getLatestContainerMetricsForServers(serverRows.map { it.id })
        val nodeRows = nodeRepository.listAll()
        sendWsRaw(filter.snapshot(serverRows, latestMetrics, nodeRows))

        // ── Subscriptions ─────────────────────────────────────────────────────
        val subscriptionsJob = launch {
            agentEvents.collect { event -> filter.toEnvelope(event)?.let { sendWsRaw(it) } }
        }

        // ── 5-min permission revalidation ─────────────────────────────────────
        val revalidationJob = launch {
            while (true) {
                delay(5.minutes)
                val stillVisible = runCatching {
                    PermissionResolver.hasPermission(userId, Permission.SERVER_VIEW) ||
                        PermissionResolver.hasPermission(userId, Permission.SYSTEM_NODES)
                }.getOrNull() ?: true // transient DB error: skip, retry next tick
                if (!stillVisible) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Permission revoked"))
                    break
                }
            }
        }

        try {
            incoming.consumeEach { }
        } finally {
            subscriptionsJob.cancel()
            revalidationJob.cancel()
        }
    }
}
