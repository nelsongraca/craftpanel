package io.craftpanel.master.routes

import com.craftpanel.agent.v1.consoleInput
import com.google.protobuf.ByteString
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.auth.WsTicketService
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.grpc.DataServiceProxy
import io.craftpanel.master.util.toKotlinUuid
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

@Serializable
private data class WsEnvelope(val type: String, val payload: JsonObject)

@Serializable
private data class WsEnvelopeIn(val type: String, val payload: JsonObject = JsonObject(emptyMap()))

private val json = Json { ignoreUnknownKeys = true }

private class ConsoleSession(val serverId: String) {
    val input = Channel<ByteArray>(Channel.BUFFERED)
    val output = MutableSharedFlow<ByteArray>(replay = 2000, extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val closed = MutableStateFlow(false)
    var job: Job? = null
}

private class ConsoleSessionManager(private val proxy: DataServiceProxy, private val scope: CoroutineScope) {
    private val log = LoggerFactory.getLogger(ConsoleSessionManager::class.java)
    private val sessions = ConcurrentHashMap<String, ConsoleSession>()

    fun getOrCreate(serverId: String): ConsoleSession =
        sessions.getOrPut(serverId) {
            val session = ConsoleSession(serverId)
            session.job = scope.launch {
                try {
                    val inputFlow = session.input.receiveAsFlow().map { bytes ->
                        consoleInput { this.serverId = serverId; data = ByteString.copyFrom(bytes) }
                    }
                    proxy.console(serverId, inputFlow).collect { output ->
                        session.output.emit(output.data.toByteArray())
                    }
                } catch (e: Exception) {
                    log.warn("Console stream for {} ended: {}", serverId, e.message)
                } finally {
                    sessions.remove(serverId)
                    session.closed.value = true
                }
            }
            session
        }

    fun close(serverId: String) {
        sessions.remove(serverId)?.apply {
            job?.cancel()
            closed.value = true
        }
    }
}

private data class ServerInfo(val serverId: UUID, val networkId: UUID?)

private fun lookupServer(rawId: String): ServerInfo? = transaction {
    val id = runCatching { UUID.fromString(rawId).toKotlinUuid() }.getOrNull() ?: return@transaction null
    val row = Servers.selectAll().where { Servers.id eq id }.firstOrNull() ?: return@transaction null
    ServerInfo(
        serverId = UUID.fromString(id.toString()),
        networkId = row[Servers.networkId]?.let { UUID.fromString(it.toString()) },
    )
}

fun Route.consoleRoutes(wsTicketService: WsTicketService, proxy: DataServiceProxy) {
    val log = LoggerFactory.getLogger("io.craftpanel.master.routes.ConsoleRoutes")
    val sessionManager = ConsoleSessionManager(proxy, CoroutineScope(SupervisorJob().plus(Dispatchers.IO)))

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

        if (!PermissionResolver.hasPermission(userId, "server.console", serverInfo.serverId, serverInfo.networkId)) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Insufficient permissions"))
            return@webSocket
        }

        // ── Session ───────────────────────────────────────────────────────
        val session = sessionManager.getOrCreate(serverId)

        fun sendJson(type: String, payload: Map<String, String>) {
            val obj = JsonObject(payload.mapValues { (_, v) -> kotlinx.serialization.json.JsonPrimitive(v) })
            val frame = json.encodeToString(WsEnvelope(type, obj))
            outgoing.trySend(Frame.Text(frame))
        }

        sendJson("console.ready", mapOf("server_id" to serverId))

        // ── Background jobs ───────────────────────────────────────────────
        val outputJob = launch {
            try {
                session.output.collect { chunk ->
                    val text = chunk.decodeToString()
                    sendJson("console.output", mapOf("data" to text))
                }
            } catch (_: Exception) {}
        }

        val revalidationJob = launch {
            while (true) {
                kotlinx.coroutines.delay(5.minutes)
                if (!PermissionResolver.hasPermission(userId, "server.console", serverInfo.serverId, serverInfo.networkId)) {
                    sendJson("console.disconnected", mapOf("server_id" to serverId, "reason" to "Session revoked"))
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Session revoked"))
                    return@launch
                }
            }
        }

        val closeWatcherJob = launch {
            session.closed.collect { isClosed ->
                if (isClosed) {
                    runCatching {
                        sendJson("console.disconnected", mapOf("server_id" to serverId, "reason" to "Server stopped"))
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
                        val envelope = json.decodeFromString<WsEnvelopeIn>(frame.readText())
                        if (envelope.type == "console.input") {
                            val data = envelope.payload["data"]?.jsonPrimitive?.content ?: ""
                            session.input.trySend(data.toByteArray())
                        }
                    }.onFailure { log.warn("Malformed console input: {}", it.message) }
                }
            }
        } finally {
            outputJob.cancel()
            revalidationJob.cancel()
            closeWatcherJob.cancel()
        }
    }
}
