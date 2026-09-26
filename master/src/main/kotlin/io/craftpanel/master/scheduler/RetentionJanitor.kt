package io.craftpanel.master.scheduler

import io.craftpanel.master.service.SettingsProvider
import io.craftpanel.master.service.repo.ContainerMetricsRepository
import io.craftpanel.master.service.repo.NodeRepository
import io.craftpanel.master.service.repo.ServerStatusHistoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Periodic maintenance that keeps the time-series tables bounded. Prunes `server_status_events`,
 * `container_metrics`, and `node_metrics` older than the configured `metric_retention_days`.
 *
 * Runs once on start, then every [pruneInterval]. Each table is pruned independently so a failure
 * on one cannot leave the others un-pruned or kill the loop.
 */
class RetentionJanitor(
    private val settingsProvider: SettingsProvider,
    private val statusHistoryRepository: ServerStatusHistoryRepository,
    private val containerMetricsRepository: ContainerMetricsRepository,
    private val nodeRepository: NodeRepository,
    private val clock: Clock = Clock.System,
    private val pruneInterval: Duration = 24.hours
) {

    private val log = LoggerFactory.getLogger(RetentionJanitor::class.java)
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            while (isActive) {
                runCatching { prune() }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        log.warn("Retention prune failed — will retry next cycle", e)
                    }
                delay(pruneInterval)
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    /** One prune pass. Internal so tests can drive it directly with a controlled clock. */
    internal fun prune() {
        val retentionDays = settingsProvider.current().metricRetentionDays
        val cutoff = clock.now() - retentionDays.days

        val statusEvents = runCatching { statusHistoryRepository.deleteOlderThan(cutoff) }
            .onFailure { log.warn("Failed to prune server status events", it) }
            .getOrDefault(0)
        val containerSamples = runCatching { containerMetricsRepository.deleteOlderThan(cutoff) }
            .onFailure { log.warn("Failed to prune container metrics", it) }
            .getOrDefault(0)
        val nodeSamples = runCatching { nodeRepository.deleteMetricsOlderThan(cutoff) }
            .onFailure { log.warn("Failed to prune node metrics", it) }
            .getOrDefault(0)

        if (statusEvents + containerSamples + nodeSamples > 0) {
            log.info(
                "Retention prune (>${retentionDays}d): removed {} status events, {} container samples, {} node samples",
                statusEvents,
                containerSamples,
                nodeSamples
            )
        }
    }
}
