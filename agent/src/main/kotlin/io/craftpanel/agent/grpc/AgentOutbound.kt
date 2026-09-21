package io.craftpanel.agent.grpc

import io.craftpanel.proto.*
import kotlinx.coroutines.channels.SendChannel
import org.slf4j.Logger

/**
 * Outbound sink for agent→master messages, split into two lanes:
 *
 * - **realtime** — console output, server status, acks and unary responses. Buffered and
 *   delivered with suspend/trySend; never displaced by telemetry.
 * - **telemetry** — node/container metrics and player updates. High-volume and delay-tolerant;
 *   backed by a `DROP_OLDEST` channel so it can never stall the collector or evict realtime
 *   traffic.
 *
 * The single-channel constructor is kept for tests, which can route both lanes to one channel.
 */
class AgentOutbound(private val realtime: SendChannel<AgentMessage>, private val telemetry: SendChannel<AgentMessage>, private val nodeId: String) {

    constructor(out: SendChannel<AgentMessage>, nodeId: String) : this(out, out, nodeId)

    suspend fun serverStatus(serverId: String, status: ServerStatusUpdate.ServerStatus) {
        val id = nodeId
        realtime.send(
            agentMessage {
                this.nodeId = id
                serverStatus = serverStatusUpdate {
                    this.serverId = serverId
                    this.status = status
                }
            }
        )
    }

    fun tryServerStatus(serverId: String, status: ServerStatusUpdate.ServerStatus) {
        val id = nodeId
        realtime.trySend(
            agentMessage {
                this.nodeId = id
                serverStatus = serverStatusUpdate {
                    this.serverId = serverId
                    this.status = status
                }
            }
        )
    }

    /**
     * The one status-reporting seam: runs [block], emits [success] when it returns (or nothing when
     * [success] is null), and emits UNHEALTHY when it throws. Best-effort — statuses go out via
     * [tryServerStatus], so a saturated realtime lane drops them rather than blocking the caller.
     */
    suspend fun withStatus(
        serverId: String,
        success: ServerStatusUpdate.ServerStatus?,
        log: Logger,
        context: String,
        block: suspend () -> Unit
    ) {
        runCatching { block() }
            .onSuccess { if (success != null && serverId.isNotEmpty()) tryServerStatus(serverId, success) }
            .onFailure { e ->
                log.error(context, e)
                if (serverId.isNotEmpty()) tryServerStatus(serverId, ServerStatusUpdate.ServerStatus.UNHEALTHY)
            }
    }

    fun tryConsoleOutput(requestId: String, build: ConsoleOutputKt.Dsl.() -> Unit) {
        val id = nodeId
        realtime.trySend(
            agentMessage {
                this.nodeId = id
                consoleOutput = consoleOutput {
                    this.requestId = requestId
                    build()
                }
            }
        )
    }

    suspend fun send(build: AgentMessageKt.Dsl.() -> Unit) {
        val id = nodeId
        realtime.send(
            agentMessage {
                this.nodeId = id
                build()
            }
        )
    }

    fun trySend(build: AgentMessageKt.Dsl.() -> Unit) {
        val id = nodeId
        realtime.trySend(
            agentMessage {
                this.nodeId = id
                build()
            }
        )
    }

    /**
     * Best-effort telemetry send. Never suspends: the telemetry channel drops the oldest buffered
     * sample when saturated, so a slow master connection cannot back-pressure the metrics loop.
     */
    fun sendTelemetry(build: AgentMessageKt.Dsl.() -> Unit) {
        val id = nodeId
        telemetry.trySend(
            agentMessage {
                this.nodeId = id
                build()
            }
        )
    }
}
