package io.craftpanel.master.service

import io.craftpanel.master.auth.ScopeType
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.domain.BackupStatus
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.service.repo.BackupRepository
import io.craftpanel.master.service.repo.ContainerMetricsRepository
import io.craftpanel.master.service.repo.NodeRepository
import io.craftpanel.master.service.repo.ServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Subscribes to the agent event bus and handles observability concerns:
 * metrics persistence, server status persistence, backup tracking,
 * crash-recovery restart, and alert evaluation.
 *
 * Owns its own coroutine lifecycle — started via [start] and cancelled
 * when the scope is cancelled.
 */
class NodeObserver(
    private val agentEvents: SharedFlow<AgentEvent>,
    private val restartManager: ServerRestartManager?,
    private val crashRestarts: SendChannel<Uuid>,
    private val emitAgentEvent: suspend (AgentEvent) -> Unit,
    private val serverRepository: ServerRepository,
    private val nodeRepository: NodeRepository,
    private val containerMetricsRepository: ContainerMetricsRepository,
    private val backupRepository: BackupRepository,
    private val alertEvaluator: AlertEvaluator,
    private val clock: Clock = Clock.System
) {

    private val log = LoggerFactory.getLogger(NodeObserver::class.java)

    fun start(scope: CoroutineScope): Job = scope.launch {
        agentEvents.collect { event ->
            try {
                when (event) {
                    is AgentEvent.NodeMetricsEvent    -> {
                        persistNodeMetrics(event)
                        evaluateNodeAlerts(event)
                    }

                    is AgentEvent.ContainerMetricsEvent -> {
                        persistContainerMetrics(event)
                        evaluateServerAlerts(event)
                    }

                    is AgentEvent.ServerStatusEvent   -> persistServerStatus(event)

                    is AgentEvent.PlayerUpdateEvent   -> persistPlayerUpdate(event)

                    is AgentEvent.BackupCompleteEvent -> persistBackupComplete(event)

                    else                              -> {
                        /* unrelated events */
                    }
                }
            }
            catch (e: Exception) {
                log.warn("NodeObserver: failed to process event {} — {}", event::class.simpleName, e.message)
            }
        }
    }

    // ── Metrics persistence ───────────────────────────────────────────────────

    private fun persistNodeMetrics(event: AgentEvent.NodeMetricsEvent) {
        val kotlinNodeId = runCatching { Uuid.parse(event.nodeId) }.getOrNull() ?: return

        nodeRepository.insertMetrics(
            nodeId = kotlinNodeId,
            cpuPercent = event.cpuPercent,
            ramUsedMb = event.ramUsedMb,
            ramTotalMb = event.ramTotalMb,
            netInBytes = event.netInBytes,
            netOutBytes = event.netOutBytes,
            diskUsedBytes = event.diskUsedBytes,
            diskTotalBytes = event.diskTotalBytes,
            recordedAt = event.recordedAt
        )
        if (event.ramUsedMb > 0) {
            nodeRepository.updateSystemRam(kotlinNodeId, event.ramUsedMb)
        }
    }

    private fun persistContainerMetrics(event: AgentEvent.ContainerMetricsEvent) {
        val kotlinServerId = runCatching { Uuid.parse(event.serverId) }.getOrNull() ?: return

        containerMetricsRepository.insertContainerMetrics(
            serverId = kotlinServerId,
            cpuPercent = event.cpuPercent,
            ramUsedMb = event.ramUsedMb,
            netInBytes = event.netInBytes,
            netOutBytes = event.netOutBytes,
            blockInBytes = event.blockInBytes,
            blockOutBytes = event.blockOutBytes,
            recordedAt = event.recordedAt
        )
    }

    private fun persistServerStatus(event: AgentEvent.ServerStatusEvent) {
        val serverId = runCatching { Uuid.parse(event.serverId) }.getOrNull() ?: return
        val now = clock.now()

        val prevStatus = serverRepository.findById(serverId)
            ?.let { ServerStatus.fromDb(it.status) }
        serverRepository.updateStatus(serverId, event.status.toDb(), now)

        maybeRestartOnCrash(serverId, prevStatus, event.status)
    }

    /**
     * App-owned crash recovery. A managed container reporting UNHEALTHY while master's desired-state
     * was running (HEALTHY/STARTING) is an unexpected death — restart it, bounded by the cap.
     * An intentional stop sets the DB to STOPPING/STOPPED first, so prevStatus is not running and no
     * restart fires. Reaching HEALTHY clears the crash counter.
     */
    private fun maybeRestartOnCrash(serverId: Uuid, prevStatus: ServerStatus?, newStatus: ServerStatus) {
        val mgr = restartManager ?: return
        if (newStatus == ServerStatus.HEALTHY) {
            mgr.reset(serverId)
            return
        }
        val crashed = newStatus == ServerStatus.UNHEALTHY &&
            (prevStatus == ServerStatus.HEALTHY || prevStatus == ServerStatus.STARTING)
        if (!crashed) return
        if (mgr.recordCrashAndShouldRestart(serverId)) {
            crashRestarts.trySend(serverId)
        }
    }

    private fun persistPlayerUpdate(event: AgentEvent.PlayerUpdateEvent) {
        val serverId = runCatching { Uuid.parse(event.serverId) }.getOrNull() ?: return
        val now = clock.now()
        val namesString = event.playerNames.joinToString(",")
            .takeIf { s -> s.isNotBlank() }

        serverRepository.updatePlayerInfo(serverId, event.playerCount, namesString, now)
    }

    private fun persistBackupComplete(event: AgentEvent.BackupCompleteEvent) {
        val backupId = runCatching { Uuid.parse(event.backupId) }.getOrNull() ?: return
        val status = if (event.success) BackupStatus.COMPLETED else BackupStatus.FAILED
        val sizeBytes = if (event.success) event.sizeBytes.takeIf { it > 0 } else null
        val errorMessage = if (!event.success) event.errorMessage.takeIf { it.isNotBlank() } else null

        backupRepository.updateBackupStatus(backupId, status, null, sizeBytes, errorMessage, event.completedAt)
    }

    // ── Alert evaluation ──────────────────────────────────────────────────────

    private suspend fun evaluateNodeAlerts(event: AgentEvent.NodeMetricsEvent) {
        val kotlinNodeId = runCatching { Uuid.parse(event.nodeId) }.getOrNull() ?: return

        val metricValues = buildMap {
            put("cpu_percent", event.cpuPercent)
            if (event.ramTotalMb > 0) {
                put("ram_percent", event.ramUsedMb.toDouble() / event.ramTotalMb * 100.0)
            }
            if (event.diskTotalBytes > 0) {
                put("disk_percent", event.diskUsedBytes.toDouble() / event.diskTotalBytes * 100.0)
            }
        }

        alertEvaluator.evaluate(ScopeType.NODE, kotlinNodeId, "Node ${event.nodeId}", metricValues)
            .forEach { emitAgentEvent(it) }
    }

    private suspend fun evaluateServerAlerts(event: AgentEvent.ContainerMetricsEvent) {
        val kotlinServerId = runCatching { Uuid.parse(event.serverId) }.getOrNull() ?: return

        val serverMemMb = serverRepository.findById(kotlinServerId)?.memoryMb ?: return

        val metricValues = buildMap {
            put("cpu_percent", event.cpuPercent)
            if (serverMemMb > 0) {
                put("ram_percent", event.ramUsedMb.toDouble() / serverMemMb * 100.0)
            }
        }

        alertEvaluator.evaluate(ScopeType.SERVER, kotlinServerId, "Server ${event.serverId}", metricValues)
            .forEach { emitAgentEvent(it) }
    }
}
