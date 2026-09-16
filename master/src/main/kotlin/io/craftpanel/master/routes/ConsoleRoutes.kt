@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.craftpanel.master.routes

import io.craftpanel.master.auth.*
import io.craftpanel.master.grpc.DataServiceProxy
import io.craftpanel.master.routes.dto.ConsoleLogsResponse
import io.craftpanel.master.service.SystemService
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val json = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
    namingStrategy = JsonNamingStrategy.SnakeCase
}

private fun DefaultWebSocketSession.sendConsole(event: ConsoleEvent) {
    outgoing.trySend(Frame.Text(json.encodeToString(ConsoleEvent.serializer(), event)))
}

fun Route.consoleRoutes(proxy: DataServiceProxy, wsAuthorization: WsAuthorization, systemService: SystemService) = with(ConsoleRoutes(proxy, wsAuthorization, systemService)) { register() }

class ConsoleRoutes(private val proxy: DataServiceProxy, private val wsAuthorization: WsAuthorization, private val systemService: SystemService) {

    private val log = LoggerFactory.getLogger(ConsoleRoutes::class.java)
    private val sessionManager = ConsoleSessionManager(proxy::console, CoroutineScope(SupervisorJob().plus(Dispatchers.IO)))

    fun Route.register() {
        // operationId: consoleWebSocket
        // Requires: ?ticket=<ws-ticket> (from POST /api/auth/ws-ticket)
        // Bidirectional: client sends console input, server streams console output.
        webSocket("/api/ws/console/{id}") {
            // ── Auth ──────────────────────────────────────────────────────────
            val grant = when (val access = wsAuthorization.authorizeServerSocket(call, Permission.SERVER_CONSOLE)) {
                is WsAuthorization.Access.Denied -> {
                    close(CloseReason(access.codes, access.message))
                    return@webSocket
                }

                is WsAuthorization.Access.Granted -> access
            }
            val serverId = grant.serverId.toString()

            // ── Session ───────────────────────────────────────────────────────
            val session = sessionManager.getOrCreate(grant.serverId)

            sendConsole(ConsoleEvent.Ready(serverId))

            // ── Background jobs ───────────────────────────────────────────────
            val outputJob = launch {
                try {
                    session.output.collect { chunk ->
                        val text = chunk.decodeToString()
                        sendConsole(ConsoleEvent.Output(text))
                    }
                } catch (_: Exception) {
                }
            }

            val revalidationJob = revalidatePeriodically(
                check = { wsAuthorization.hasPermission(grant.userId, Permission.SERVER_CONSOLE, grant.serverId, grant.networkId) }
            ) {
                sendConsole(ConsoleEvent.Disconnected(serverId, "Session revoked"))
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Session revoked"))
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
            } finally {
                outputJob.cancel()
                revalidationJob.cancel()
                closeWatcherJob.cancel()
                sessionManager.releaseViewer(grant.serverId)
            }
        }

        // Static container logs — fallback for crash diagnosis when live attach isn't possible (server UNHEALTHY)
        authenticate(JWT_AUTH) {
            get("/api/servers/{id}/console/logs", {
                operationId = "fetchServerConsoleLogs"
                summary = "Fetch static container logs (tail, no follow) for crash diagnosis"
                request {
                    pathParameter<String>("id")
                    queryParameter<Int>("tail") { required = false }
                }
                response {
                    code(HttpStatusCode.OK) { body<ConsoleLogsResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val auth = call.requireServerPermission(Permission.SERVER_CONSOLE)

                val defaultTail = systemService.getSettings().settings.consoleTailLines
                val tailLines = (call.request.queryParameters["tail"]?.toIntOrNull() ?: defaultTail).coerceIn(1, 5000)
                val lines = proxy.fetchContainerLogs(auth.serverId, tailLines)
                call.respond(ConsoleLogsResponse(lines))
            }
        }
    }
}
