@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.craftpanel.master.routes

import io.craftpanel.master.auth.*
import io.craftpanel.master.service.DashboardService
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlin.time.Duration.Companion.minutes

internal val wsJson = Json {
    ignoreUnknownKeys = true
    namingStrategy = JsonNamingStrategy.SnakeCase
}

private fun DefaultWebSocketSession.sendWsRaw(envelope: WsEnvelope) {
    outgoing.trySend(Frame.Text(wsJson.encodeToString(envelope)))
}

fun Route.dashboardWsRoutes(wsTicketService: WsTicketService, dashboardService: DashboardService) {
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

        // ── Initial snapshot ──────────────────────────────────────────────────
        sendWsRaw(dashboardService.getSnapshot(userId))

        // ── Subscriptions ─────────────────────────────────────────────────────
        val subscriptionsJob = launch {
            dashboardService.filteredEvents(userId)
                .collect { sendWsRaw(it) }
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
