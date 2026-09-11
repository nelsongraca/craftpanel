package io.craftpanel.agent.grpc

import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.*
import io.craftpanel.agent.grpc.handlers.nowTimestamp
import io.craftpanel.proto.*
import io.grpc.ManagedChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

class ControlStreamHandler(
    private val identity: NodeIdentity,
    private val config: AgentConfig,
    private val containerManager: ContainerManager,
    private val metricsCollector: MetricsCollector,
    private val routerSupervisor: RouterSupervisor,
    private val eventWatcher: ContainerEventWatcher,
    private val dispatcher: CommandDispatcher,
    private val gate: WatcherGate,
) {

    private val log = LoggerFactory.getLogger(ControlStreamHandler::class.java)

    suspend fun run(channel: ManagedChannel): Unit = coroutineScope {
        val stub = ControlServiceGrpcKt.ControlServiceCoroutineStub(channel)
        val outboundChannel = Channel<AgentMessage>(capacity = 64)
        val out = AgentOutbound(outboundChannel, identity.nodeId)

        val stream = stub.control(outboundChannel.receiveAsFlow())

        // Send NodeStateSnapshot as the first message
        val snapshot = buildStateSnapshot()
        outboundChannel.send(
            agentMessage {
                nodeId = identity.nodeId
                nodeState = snapshot
            }
        )
        log.info("Sent NodeStateSnapshot with ${snapshot.containersCount} containers")

        // Periodic metrics loop
        val metricsInterval = config.metricsPollIntervalSeconds.toLong().seconds
        launch {
            while (true) {
                delay(metricsInterval)
                Heartbeat.beat()
                val routerRunning = routerSupervisor.isRunning
                val metrics = metricsCollector.collect()
                out.send {
                    nodeMetrics = metrics.toBuilder()
                        .setRouterRunning(routerRunning)
                        .build()
                }

                containerManager.listRunningContainerIds()
                    .forEach { (serverId, containerId) ->
                        metricsCollector.collectContainerMetrics(serverId, containerId)
                            ?.let { cm -> out.send { containerMetrics = cm } }
                        metricsCollector.collectPlayerCount(serverId, containerId)
                            ?.let { pu -> out.send { playerUpdate = pu } }
                    }
            }
        }

        // Near-instant crash signal: report managed-container deaths to master immediately.
        // Master decides whether to restart (bounded). Closed when this stream scope ends;
        // the periodic snapshot reconcile is the backstop if an event is missed.
        val eventStream = eventWatcher.watch(
            shouldReport = gate::shouldReportDie,
            onContainerCrash = { serverId -> out.tryServerStatus(serverId, ServerStatusUpdate.ServerStatus.UNHEALTHY) },
            onContainerStopped = { serverId -> out.tryServerStatus(serverId, ServerStatusUpdate.ServerStatus.STOPPED) }
        )
        coroutineContext.job.invokeOnCompletion { runCatching { eventStream.close() } }

        // Process inbound commands from master
        stream.collect { msg ->
            log.debug("Received master command: {}", msg.payloadCase)
            dispatcher.dispatch(msg, out, this)
        }
    }

    private fun isSwarmActive(): Boolean = containerManager.isSwarmActive()

    internal fun buildStateSnapshot(): NodeStateSnapshot {
        val containers = containerManager.listContainers()
        return nodeStateSnapshot {
            this.containers.addAll(containers)
            recordedAt = nowTimestamp()
            routerRunning = routerSupervisor.isRunning
            swarmActive = isSwarmActive()
        }
    }
}
