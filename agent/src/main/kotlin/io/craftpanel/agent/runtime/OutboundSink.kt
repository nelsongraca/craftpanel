package io.craftpanel.agent.runtime

import io.craftpanel.agent.grpc.AgentOutbound
import io.craftpanel.proto.AgentMessageKt
import io.craftpanel.proto.ServerStatusUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.Logger

/**
 * Process-scoped emission gate for the loops that outlive a connection (convergence, metrics).
 *
 * The concrete [AgentOutbound] is connection-scoped: [attach] on stream open, [detach] on stream
 * death. While detached every emit is a no-op (there is nowhere to send it) and [connected] is
 * false, which is what [io.craftpanel.agent.grpc.MetricsPump] waits on instead of polling Docker for
 * samples that could only be dropped.
 */
class OutboundSink {

    @Volatile
    private var outbound: AgentOutbound? = null

    private val _connected = MutableStateFlow(false)

    /** True while a control stream is open and [outbound] can accept messages. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun attach(out: AgentOutbound) {
        outbound = out
        _connected.value = true
    }

    fun detach() {
        outbound = null
        _connected.value = false
    }

    fun tryServerStatus(serverId: String, status: ServerStatusUpdate.ServerStatus) {
        outbound?.tryServerStatus(serverId, status)
    }

    /**
     * Runs [block] and reports [success]/UNHEALTHY when a connection is open. Unlike the underlying
     * [AgentOutbound.withStatus], the block always runs — convergence must proceed offline — only the
     * status emission is skipped.
     */
    suspend fun withStatus(
        serverId: String,
        success: ServerStatusUpdate.ServerStatus?,
        log: Logger,
        context: String,
        block: suspend () -> Unit
    ) {
        val out = outbound
        runCatching { block() }
            .onSuccess { if (out != null && success != null && serverId.isNotEmpty()) out.tryServerStatus(serverId, success) }
            .onFailure { e ->
                log.error(context, e)
                if (out != null && serverId.isNotEmpty()) out.tryServerStatus(serverId, ServerStatusUpdate.ServerStatus.UNHEALTHY)
            }
    }

    /**
     * Best-effort telemetry send. Never suspends; no-op while detached (the metrics pump is gated on
     * [connected], so this only fires in the connect/disconnect race window).
     */
    fun sendTelemetry(build: AgentMessageKt.Dsl.() -> Unit) {
        outbound?.sendTelemetry(build)
    }
}
