package io.craftpanel.agent.grpc

import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.docker.RouterSupervisor
import io.craftpanel.agent.grpc.handlers.nowTimestamp
import io.craftpanel.proto.AgentMessage
import io.craftpanel.proto.ControlServiceGrpcKt
import io.craftpanel.proto.NodeStateSnapshot
import io.craftpanel.proto.nodeStateSnapshot
import io.grpc.ManagedChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Owns one control stream: sends the opening node-state snapshot, bridges the telemetry lane into
 * the request stream, and dispatches inbound commands.
 *
 * The long-lived loops (convergence, reconcile sweep, metrics pump, Docker event watcher) are
 * deliberately *not* started here — they live in
 * [io.craftpanel.agent.runtime.AgentRuntime] so master downtime cannot disable crash-restart.
 */
class ControlStreamHandler(
    private val containerManager: ContainerManager,
    private val routerSupervisor: RouterSupervisor,
    private val dispatcher: CommandDispatcher,
    private val out: AgentOutbound
) {

    private val log = LoggerFactory.getLogger(ControlStreamHandler::class.java)

    suspend fun run(channel: ManagedChannel, realtimeChannel: Channel<AgentMessage>, telemetryChannel: Channel<AgentMessage>): Unit = coroutineScope {
        val stub = ControlServiceGrpcKt.ControlServiceCoroutineStub(channel)

        // Send NodeStateSnapshot as the first message. It is buffered on the realtime lane BEFORE
        // the request flow starts, so no telemetry frame can precede it in the stream.
        val snapshot = buildStateSnapshot()
        out.send {
            nodeState = snapshot
        }
        log.info(
            "Sent NodeStateSnapshot with ${snapshot.containersCount} containers: {}",
            snapshot.containersList.joinToString { "${it.serverId.ifEmpty { "?" }}=${it.runState}" }
        )

        // The gRPC client-stream is fed by the realtime lane. Realtime messages (console, status,
        // acks) can never be displaced by telemetry backlog.
        val stream = stub.control(realtimeChannel.receiveAsFlow())

        // Best-effort telemetry pump: forwards the delay-tolerant lane into the request stream
        // only when there is room. A full realtime lane drops telemetry rather than displacing it,
        // and the metrics loop never blocks because its own channel is DROP_OLDEST.
        launch {
            telemetryChannel.receiveAsFlow()
                .collect { msg -> realtimeChannel.trySend(msg) }
        }

        // Process inbound commands from master
        stream.collect { msg ->
            log.debug("Received master command: {}", msg.payloadCase)
            dispatcher.dispatch(msg, out, this)
        }
    }

    internal fun buildStateSnapshot(): NodeStateSnapshot {
        val containers = containerManager.listContainers()
        return nodeStateSnapshot {
            this.containers.addAll(containers)
            recordedAt = nowTimestamp()
            routerRunning = routerSupervisor.isRunning
            swarmActive = containerManager.isSwarmActive()
        }
    }
}
