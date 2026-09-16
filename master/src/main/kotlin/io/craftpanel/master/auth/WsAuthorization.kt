package io.craftpanel.master.auth

import io.ktor.server.application.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * The one seam that authorizes a WebSocket connection. Sockets cannot answer with an HTTP status,
 * so a failed check is returned as [Access.Denied] carrying the close code + message, and the route
 * closes the session. Replaces the ticket-consume → scope-lookup → permission-check prelude that
 * was copy-pasted across the console, migration, and dashboard sockets.
 */
class WsAuthorization(private val wsTicketService: WsTicketService, private val permissionResolver: PermissionResolver) {

    sealed interface Access {

        data class Granted(val userId: Uuid, val serverId: Uuid, val networkId: Uuid?) : Access

        data class Denied(val message: String, val codes: CloseReason.Codes = CloseReason.Codes.VIOLATED_POLICY) : Access
    }

    /** Consume the `?ticket=` WS ticket, or null when absent or invalid/expired. */
    fun consumeTicket(call: ApplicationCall): Uuid? = call.request.queryParameters["ticket"]?.let { wsTicketService.consume(it) }

    /**
     * Authorize a server-scoped socket: consume the ticket, resolve the server (from [serverId] or
     * the `{id}` path parameter), and check [permission].
     */
    fun authorizeServerSocket(call: ApplicationCall, permission: Permission, serverId: Uuid? = null): Access {
        val rawTicket = call.request.queryParameters["ticket"]
            ?: return Access.Denied("Missing ticket")
        val userId = wsTicketService.consume(rawTicket)
            ?: return Access.Denied("Invalid or expired ticket")
        val id = serverId ?: call.parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: return Access.Denied("Missing server ID", CloseReason.Codes.NORMAL)
        val scope = ServerLookup.scope(id)
            ?: return Access.Denied("Server not found", CloseReason.Codes.NORMAL)
        if (!permissionResolver.hasPermission(userId, permission, id, scope.networkId)) {
            return Access.Denied("Insufficient permissions")
        }
        return Access.Granted(userId, id, scope.networkId)
    }

    /** Re-check a socket's permission (used by the periodic revalidation loop). */
    fun hasPermission(userId: Uuid, permission: Permission, serverId: Uuid? = null, networkId: Uuid? = null): Boolean = permissionResolver.hasPermission(userId, permission, serverId, networkId)
}

/**
 * Runs [check] every [interval] on the socket's scope; when it returns false, runs [onRevoked] and
 * stops. A transient failure (exception) is treated as "still allowed" and retried next tick, so a
 * momentary DB error never drops a healthy session.
 */
fun CoroutineScope.revalidatePeriodically(interval: Duration = 5.minutes, check: () -> Boolean, onRevoked: suspend () -> Unit): Job = launch {
    while (true) {
        delay(interval)
        if (!runCatching { check() }.getOrDefault(true)) {
            onRevoked()
            break
        }
    }
}
