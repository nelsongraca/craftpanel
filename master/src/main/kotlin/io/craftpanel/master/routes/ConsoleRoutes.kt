@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.craftpanel.master.routes

import io.craftpanel.master.auth.Permission
import com.craftpanel.agent.v1.consoleInput
import com.google.protobuf.ByteString
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.auth.WsTicketService
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.grpc.DataServiceProxy
import io.craftpanel.master.util.toKotlinUuid
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type"; namingStrategy = JsonNamingStrategy.SnakeCase }

private fun DefaultWebSocketSession.sendConsole(event: ConsoleEvent) {
    outgoing.trySend(Frame.Text(json.encodeToString(ConsoleEvent.serializer(), event)))
}

private class ConsoleSession {

    val input = Channel<ByteArray>(Channel.BUFFERED)
    val output = MutableSharedFlow<ByteArray>(
        replay = 2000, extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val closed = MutableStateFlow(false)
    var job: Job? = null
}

private class ConsoleSessionManager(private val proxy: DataServiceProxy, private val scope: CoroutineScope) {

    private val log = LoggerFactory.getLogger(ConsoleSessionManager::class.java)
    private val sessions = ConcurrentHashMap<String, ConsoleSession>()

    fun getOrCreate(serverId: String): ConsoleSession =
        sessions.getOrPut(serverId) {
            val session = ConsoleSession()
            session.job = scope.launch {
                try {
                    val inputFlow = session.input.receiveAsFlow()
                        .map { bytes ->
                            consoleInput { this.serverId = serverId; data = ByteString.copyFrom(bytes) }
                        }
                    proxy.console(serverId, inputFlow)
                        .collect { output ->
                            session.output.emit(output.data.toByteArray())
                        }
                }
                catch (e: Exception) {
                    log.warn("Console stream for {} ended: {}", serverId, e.message)
                }
                finally {
                    sessions.remove(serverId)
                    session.closed.value = true
                }
            }
            session
        }

}

private data class ServerInfo(val serverId: UUID, val networkId: UUID?)

private fun lookupServer(rawId: String): ServerInfo? = transaction {
    val id = runCatching {
        UUID.fromString(rawId)
            .toKotlinUuid()
    }.getOrNull() ?: return@transaction null
    val row = Servers.selectAll()
        .where { Servers.id eq id }
        .firstOrNull() ?: return@transaction null
    ServerInfo(
        serverId = UUID.fromString(id.toString()),
        networkId = row[Servers.networkId]?.let { UUID.fromString(it.toString()) },
    )
}

fun Route.consoleRoutes(wsTicketService: WsTicketService, proxy: DataServiceProxy) {
    val log = LoggerFactory.getLogger("io.craftpanel.master.routes.ConsoleRoutes")
    val sessionManager = ConsoleSessionManager(proxy, CoroutineScope(SupervisorJob().plus(Dispatchers.IO)))

    // operationId: consoleWebSocket
    // Requires: ?ticket=<ws-ticket> (from POST /api/auth/ws-ticket)
    // Bidirectional: client sends console input, server streams console output.
    webSocket("/api/ws/console/{id}") {
        // ── Auth ──────────────────────────────────────────────────────────
        val ticket = call.request.queryParameters["ticket"] ?: run {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing ticket"))
            return@webSocket
        }
        val userId = wsTicketService.consume(ticket) ?: run {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid or expired ticket"))
            return@webSocket
        }

        val serverId = call.parameters["id"] ?: run {
            close(CloseReason(CloseReason.Codes.NORMAL, "Missing server ID"))
            return@webSocket
        }
        val serverInfo = lookupServer(serverId) ?: run {
            close(CloseReason(CloseReason.Codes.NORMAL, "Server not found"))
            return@webSocket
        }

        if (!PermissionResolver.hasPermission(userId, Permission.SERVER_CONSOLE, serverInfo.serverId, serverInfo.networkId)) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Insufficient permissions"))
            return@webSocket
        }

        // ── Session ───────────────────────────────────────────────────────
        val session = sessionManager.getOrCreate(serverId)

        sendConsole(ConsoleEvent.Ready(serverId))

        // ── Background jobs ───────────────────────────────────────────────
        val outputJob = launch {
            try {
                session.output.collect { chunk ->
                    val text = chunk.decodeToString()
                    sendConsole(ConsoleEvent.Output(text))
                }
            }
            catch (_: Exception) {
            }
        }

        val revalidationJob = launch {
            while (true) {
                delay(5.minutes)
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_CONSOLE, serverInfo.serverId, serverInfo.networkId)) {
                    sendConsole(ConsoleEvent.Disconnected(serverId, "Session revoked"))
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Session revoked"))
                    return@launch
                }
            }
        }

        val closeWatcherJob = launch {
            session.closed.collect { isClosed ->
                if (isClosed) {
                    runCatching {
                        sendConsole(ConsoleEvent.Disconnected(serverId, "Server stopped"))
                        close(CloseReason(CloseReason.Codes.NORMAL, "Server stopped"))
                    }
                }
            }
        }

        // ── Input loop ────────────────────────────────────────────────────
        try {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    runCatching {
                        val event = json.decodeFromString(ConsoleInEvent.serializer(), frame.readText())
                        if (event is ConsoleInEvent.Input) {
                            session.input.trySend(event.data.toByteArray())
                        }
                    }.onFailure { log.warn("Malformed console input: {}", it.message) }
                }
            }
        }
        finally {
            outputJob.cancel()
            revalidationJob.cancel()
            closeWatcherJob.cancel()
        }
    }
}
