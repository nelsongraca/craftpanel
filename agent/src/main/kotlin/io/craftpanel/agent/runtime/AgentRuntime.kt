package io.craftpanel.agent.runtime

import io.craftpanel.agent.config.RuntimeSettingsStore
import io.craftpanel.agent.desired.ConvergenceLoop
import io.craftpanel.agent.docker.ContainerEventWatcher
import io.craftpanel.agent.docker.WatcherGate
import io.craftpanel.agent.grpc.MetricsPump
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

/**
 * Process-scoped owner of the loops that must outlive a control stream: the convergence crash
 * signal, the reconcile backstop sweep and the metrics pump.
 *
 * Keeping these here (not per-connection) is what lets an agent keep crash-restarting servers while
 * master is unreachable — metrics emission is the only part that is deliberately gated on
 * connectivity, because a sample collected during an outage could only be dropped.
 */
class AgentRuntime(
    private val scope: CoroutineScope,
    private val loop: ConvergenceLoop,
    private val metricsPump: MetricsPump,
    private val eventWatcher: ContainerEventWatcher,
    private val gate: WatcherGate,
    private val settingsStore: RuntimeSettingsStore
) {

    private val log = LoggerFactory.getLogger(AgentRuntime::class.java)

    fun start() {
        // Periodic metrics loop. It waits on connectivity internally, so it survives outages.
        scope.launch { metricsPump.run() }

        // Backstop: periodically re-converge servers with intent that are not running. Catches
        // deaths the Docker event stream never delivered (agent/Docker daemon restart, dropped
        // stream) instead of leaving the server down until the next reconnect.
        scope.launch { reconcileLoop() }

        // Near-instant crash signal: unexpected deaths feed the convergence loop. Authored deaths are
        // suppressed by the WatcherGate; the watcher self-heals and the sweep above is the second
        // backstop.
        eventWatcher.watch(
            scope = scope,
            shouldReport = gate::shouldReportDie,
            onContainerDie = { serverId -> loop.onContainerDie(serverId) }
        )
        log.info("Agent runtime started (convergence + metrics + event watcher)")
    }

    private suspend fun reconcileLoop() {
        while (scope.isActive) {
            val interval = settingsStore.current().reconcileIntervalSeconds
            if (interval <= 0) {
                log.warn("Convergence reconcile sweep disabled (reconcile_interval_seconds=0)")
                // Re-check periodically so enabling it again through settings takes effect.
                delay(DISABLED_RECHECK)
                continue
            }
            delay(interval.seconds)
            runCatching { loop.reconcileAll() }
                .onFailure { log.warn("Periodic reconciliation sweep failed", it) }
        }
    }

    private companion object {

        val DISABLED_RECHECK = 60.seconds
    }
}
