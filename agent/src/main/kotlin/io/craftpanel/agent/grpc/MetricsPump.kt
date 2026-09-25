package io.craftpanel.agent.grpc

import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.desired.JvmMetricsPolicy
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * The one owner of the periodic metrics loop: node metrics, per-container metrics, player counts,
 * and (at its own, slower cadence) JVM heap metrics, emitted on the telemetry lane. Container
 * collection is fanned out concurrently (bounded by [AgentConfig.metricsCollectionConcurrency]) so a
 * node with many servers refreshes each server at roughly the poll interval rather than the sum of
 * every call.
 *
 * The interval is applied AFTER a tick completes, not as a fixed wall-clock cadence: each server's
 * probe (Docker stats plus an `mc-monitor` exec) is real work on the node, so a tick that ran long
 * must not immediately start the next one and multiply that load.
 *
 * JVM sampling is throttled separately by the per-server [JvmMetricsPolicy] (a global interval from
 * system settings): it costs an extra `docker exec` and can safepoint the server's JVM, so it runs
 * far less often than the container tick.
 */
class MetricsPump(
    private val config: AgentConfig,
    private val containerManager: ContainerManager,
    private val metricsCollector: MetricsCollector,
    private val routerSupervisor: RouterSupervisor,
    private val out: AgentOutbound,
    private val cpuLimitMillicores: (String) -> Int,
    /** Per-server player-count probe input, or null when the server cannot be probed. */
    private val playerCountProbe: (String) -> PlayerCountProbe? = { null },
    /** JVM-metrics policy for a server this agent owns, or null when it is not ours to sample. */
    private val jvmMetricsPolicy: (String) -> JvmMetricsPolicy? = { null }
) {

    private val log = LoggerFactory.getLogger(MetricsPump::class.java)
    private val statsSemaphore = Semaphore(config.metricsCollectionConcurrency)

    /** Wall-clock millis of the last JVM sample per server, for the independent throttle. */
    private val lastJvmSampleMillis = ConcurrentHashMap<String, Long>()

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

        val nowMillis = System.currentTimeMillis()
        val containers = containerManager.listRunningContainers()
        coroutineScope {
            containers.map { container ->
                async(Dispatchers.IO) {
                    statsSemaphore.withPermit {
                        val base = metricsCollector.collectContainerMetrics(
                            container.serverId,
                            container.containerId,
                            cpuLimitMillicores(container.serverId)
                        )
                        if (base != null) {
                            val policy = jvmMetricsPolicy(container.serverId)
                            val sampleJvm = policy != null && policy.enabled &&
                                jvmSampleDue(container.serverId, nowMillis, policy.pollIntervalSeconds)
                            val withJvm = if (sampleJvm) {
                                metricsCollector.collectJvmStats(container.containerId)
                                    ?.let { base.toBuilder().setJvm(it).build() }
                                    ?: base
                            } else {
                                base
                            }
                            out.sendTelemetry { containerMetrics = withJvm }
                        }

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

    /**
     * True when [serverId] is due for a JVM sample, atomically recording the sample time. Uses
     * wall-clock elapsed time rather than a tick counter so a slow tick cannot bunch samples up.
     */
    private fun jvmSampleDue(serverId: String, nowMillis: Long, intervalSeconds: Int): Boolean {
        val intervalMillis = intervalSeconds.coerceAtLeast(1) * 1000L
        var due = false
        lastJvmSampleMillis.compute(serverId) { _, last ->
            if (nowMillis - (last ?: 0L) >= intervalMillis) {
                due = true
                nowMillis
            } else {
                last
            }
        }
        return due
    }
}
