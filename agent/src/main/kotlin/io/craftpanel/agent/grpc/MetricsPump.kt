package io.craftpanel.agent.grpc

import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.docker.MetricsCollector
import io.craftpanel.agent.docker.PlayerCountProbe
import io.craftpanel.agent.docker.RouterSupervisor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

/**
 * The one owner of the periodic metrics loop: node metrics, per-container metrics, and player
 * counts, emitted on the telemetry lane. Container collection is fanned out concurrently (bounded
 * by [AgentConfig.metricsCollectionConcurrency]) so a node with many servers refreshes each server
 * at roughly the poll interval rather than the sum of every call.
 *
 * The interval is applied AFTER a tick completes, not as a fixed wall-clock cadence: each server's
 * probe (Docker stats plus an `mc-monitor` exec) is real work on the node, so a tick that ran long
 * must not immediately start the next one and multiply that load.
 */
class MetricsPump(
    private val config: AgentConfig,
    private val containerManager: ContainerManager,
    private val metricsCollector: MetricsCollector,
    private val routerSupervisor: RouterSupervisor,
    private val out: AgentOutbound,
    private val cpuLimitMillicores: (String) -> Int,
    /** Per-server player-count probe input, or null when the server cannot be probed. */
    private val playerCountProbe: (String) -> PlayerCountProbe? = { null }
) {

    private val log = LoggerFactory.getLogger(MetricsPump::class.java)
    private val statsSemaphore = Semaphore(config.metricsCollectionConcurrency)

    suspend fun run() {
        val interval = config.metricsPollIntervalSeconds.toLong().seconds
        while (true) {
            runCatching { tick() }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    log.warn("Metrics tick failed — keeping the stream alive", e)
                }
            delay(interval)
        }
    }

    /** One metrics tick. Internal so it can be driven directly in tests. */
    internal suspend fun tick() {
        Heartbeat.beat()
        val routerRunning = routerSupervisor.isRunning
        val metrics = metricsCollector.collect()
        out.sendTelemetry {
            nodeMetrics = metrics.toBuilder()
                .setRouterRunning(routerRunning)
                .build()
        }

        val containers = containerManager.listRunningContainers()
        coroutineScope {
            containers.map { container ->
                async(Dispatchers.IO) {
                    statsSemaphore.withPermit {
                        metricsCollector.collectContainerMetrics(
                            container.serverId,
                            container.containerId,
                            cpuLimitMillicores(container.serverId)
                        )
                            ?.let { cm -> out.sendTelemetry { containerMetrics = cm } }

                        val probe = playerCountProbe(container.serverId)
                        if (probe != null) {
                            metricsCollector.collectPlayerCount(
                                container.serverId,
                                container.containerId,
                                probe.internalListenPort,
                                probe.useProxyProtocol
                            )
                                ?.let { pu -> out.sendTelemetry { playerUpdate = pu } }
                        }
                    }
                }
            }.awaitAll()
        }
    }
}
