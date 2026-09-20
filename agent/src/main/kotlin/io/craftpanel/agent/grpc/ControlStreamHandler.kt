package io.craftpanel.agent.grpc

import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.desired.ConvergenceLoop
import io.craftpanel.agent.docker.*
import io.craftpanel.agent.grpc.handlers.nowTimestamp
import io.craftpanel.proto.*
import io.grpc.ManagedChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val out: AgentOutbound,
    private val loop: ConvergenceLoop,
    private val convergenceScope: CoroutineScope
) {

    private val log = LoggerFactory.getLogger(ControlStreamHandler::class.java)

    suspend fun run(channel: ManagedChannel, realtimeChannel: Channel<AgentMessage>, telemetryChannel: Channel<AgentMessage>): Unit = coroutineScope {
        val stub = ControlServiceGrpcKt.ControlServiceCoroutineStub(channel)

        // The desired-state convergence loop runs as long as this connection; cancel its jobs
        // (crash-restart, pending converge) when the stream/scope dies.
        coroutineContext.job.invokeOnCompletion { convergenceScope.cancel() }

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

        // Periodic metrics loop. Emit once immediately on connect so a freshly-opened detail page
        // has live data without waiting a full poll interval. Container collection is fanned out
        // concurrently (bounded) so a node with many servers refreshes each server at roughly the
        // poll interval rather than the sum of every call.
        val metricsInterval = config.metricsPollIntervalSeconds.toLong().seconds
        val statsSemaphore = Semaphore(config.metricsCollectionConcurrency)
        launch {
            while (true) {
                runCatching {
                    Heartbeat.beat()
                    val routerRunning = routerSupervisor.isRunning
                    val metrics = metricsCollector.collect()
                    out.sendTelemetry {
                        nodeMetrics = metrics.toBuilder()
                            .setRouterRunning(routerRunning)
                            .build()
                    }

                    val containers = containerManager.listRunningContainers()
                    val routerIp = metricsCollector.getMcRouterIp()
                    coroutineScope {
                        containers.map { container ->
                            async(Dispatchers.IO) {
                                statsSemaphore.withPermit {
                                    metricsCollector.collectContainerMetrics(
                                        container.serverId,
                                        container.containerId,
                                        loop.cpuLimitMillicores(container.serverId)
                                    )
                                        ?.let { cm -> out.sendTelemetry { containerMetrics = cm } }

                                    val routingHost = container.routingHost
                                    if (routerIp != null && !routingHost.isNullOrBlank()) {
                                        metricsCollector.collectPlayerCount(container.serverId, routerIp, routingHost)
                                            ?.let { pu -> out.sendTelemetry { playerUpdate = pu } }
                                    }
                                }
                            }
                        }
                            .awaitAll()
                    }
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    log.warn("Metrics tick failed — keeping the stream alive", e)
                }

                // Delay AFTER collection (not a fixed wall-clock cadence): the player-count pings
                // share mc-router with the data path, so a tick that ran long must not immediately
                // start the next one and multiply that load.
                delay(metricsInterval)
            }
        }

        // Backstop: periodically re-converge servers with intent that are not running. Catches
        // deaths the Docker event stream never delivered (agent/Docker daemon restart, dropped
        // stream) instead of leaving the server down until the next reconnect.
        if (config.reconcileIntervalSeconds > 0) {
            val reconcileInterval = config.reconcileIntervalSeconds.toLong().seconds
            launch {
                while (true) {
                    delay(reconcileInterval)
                    runCatching { loop.reconcileAll() }
                        .onFailure { log.warn("Periodic reconciliation sweep failed", it) }
                }
            }
        } else {
            log.warn("Convergence reconcile sweep disabled (reconcileIntervalSeconds=0)")
        }

        // Near-instant crash signal: unexpected deaths feed the convergence loop, which decides
        // restart (within budget) vs report. Authored deaths are suppressed by the WatcherGate;
        // the watcher self-heals (exponential-backoff resubscribe) and the reconcile sweep above
        // is the second backstop.
        val eventStream = eventWatcher.watch(
            scope = this,
            shouldReport = gate::shouldReportDie,
            onContainerCrash = { serverId -> loop.onContainerDie(serverId, exitCode = 1) },
            onContainerStopped = { serverId -> loop.onContainerDie(serverId, exitCode = 0) }
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
