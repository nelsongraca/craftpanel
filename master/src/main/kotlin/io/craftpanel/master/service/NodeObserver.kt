package io.craftpanel.master.service

import io.craftpanel.master.auth.ScopeType
import io.craftpanel.master.database.entity.*
import io.craftpanel.master.database.schema.*
import io.craftpanel.master.domain.*
import io.craftpanel.master.service.repo.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant
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

    // Servers for which a crash or unexpected graceful stop has been dispatched to the restart
    // loop and has not yet recovered (HEALTHY) or been superseded. Guards against dispatching a
    // second restart for the same death episode (several status events fire per death: die watcher
    // + console-teardown STOPPED + snapshot UNHEALTHY). Cleared when the server reaches HEALTHY.
    private val restartInFlight = ConcurrentHashMap.newKeySet<Uuid>()

    fun start(scope: CoroutineScope): Job = scope.launch {
        agentEvents.collect { event ->
            try {
                when (event) {
                    is AgentEvent.NodeMetricsEvent -> {
                        persistNodeMetrics(event)
                        evaluateNodeAlerts(event)
                    }

                    is AgentEvent.ContainerMetricsEvent -> {
                        persistContainerMetrics(event)
                        evaluateServerAlerts(event)
                    }

                    is AgentEvent.ServerStatusEvent -> persistServerStatus(event)

                    is AgentEvent.PlayerUpdateEvent -> persistPlayerUpdate(event)

                    is AgentEvent.BackupCompleteEvent -> persistBackupComplete(event)

                    else -> {
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

        transaction {
            NodeMetricsRecord.new {
                this.nodeId = EntityID(kotlinNodeId, Nodes)
                this.cpuPercent = event.cpuPercent
                this.ramUsedMb = event.ramUsedMb
                this.ramTotalMb = event.ramTotalMb
                this.netInBytes = event.netInBytes
                this.netOutBytes = event.netOutBytes
                this.diskUsedBytes = event.diskUsedBytes
                this.diskTotalBytes = event.diskTotalBytes
                this.recordedAt = event.recordedAt.toLocalDateTime(TimeZone.UTC)
            }
        }
        if (event.ramUsedMb > 0) {
            transaction {
                Node.findById(kotlinNodeId)
                    ?.let { it.systemRamUsedMb = event.ramUsedMb }
            }
        }
        transaction {
            Node.findById(kotlinNodeId)
                ?.let { it.systemCpuPercent = event.cpuPercent }
        }
    }

    private fun persistContainerMetrics(event: AgentEvent.ContainerMetricsEvent) {
        val kotlinServerId = runCatching { Uuid.parse(event.serverId) }.getOrNull() ?: return

        transaction {
            ContainerMetricsRecord.new {
                this.serverId = EntityID(kotlinServerId, Servers)
                this.cpuPercent = event.cpuPercent
                this.ramUsedMb = event.ramUsedMb
                this.netInBytes = event.netInBytes
                this.netOutBytes = event.netOutBytes
                this.blockInBytes = event.blockInBytes
                this.blockOutBytes = event.blockOutBytes
                this.recordedAt = event.recordedAt.toLocalDateTime(TimeZone.UTC)
            }
        }
    }

    private fun persistServerStatus(event: AgentEvent.ServerStatusEvent) {
        val serverId = runCatching { Uuid.parse(event.serverId) }.getOrNull() ?: return
        val now = clock.now()
        val prevStatus = serverRepository.findById(serverId)
            ?.let { ServerStatus.fromDb(it.status) }

        // A STOPPED event with no prior STOPPING/STOPPED in the DB means the container stopped
        // (exit 0) without a platform stop request — an in-game /stop or unexpected death with
        // exit 0. Treat as a crash: route into the restart machinery instead of persisting STOPPED
        // (which would wedge the DB and cause subsequent API calls to 409).
        val unexpectedStop = event.status == ServerStatus.STOPPED &&
            prevStatus != ServerStatus.STOPPED && prevStatus != ServerStatus.STOPPING
        if (unexpectedStop) {
            val restarting = handleUnexpectedStop(serverId, prevStatus)
            if (restarting) return
        }

        transaction {
            Server.findById(serverId)
                ?.let {
                    it.status = event.status.toDb()
                    it.lastSeenAt = now.toLocalDateTime(TimeZone.UTC)
                }
        }

        maybeRestartOnCrash(serverId, prevStatus, event.status)
    }

    /**
     * Handles a container stop that master did NOT request (no prior STOPPING/STOPPED). A graceful
     * stop (exit 0) with the DB still in a running state is an unexpected death — route it into
     * the crash-restart machinery. Returns true when a restart is dispatched (or a duplicate of an
     * in-flight death is swallowed) so the caller skips persisting STOPPED; false when no restart
     * can fire (cap exhausted or manager disabled) so the caller persists the real state.
     */
    private fun handleUnexpectedStop(serverId: Uuid, prevStatus: ServerStatus?): Boolean {
        val mgr = restartManager ?: return false
        if (restartInFlight.contains(serverId)) return true
        if (mgr.recordCrashAndShouldRestart(serverId)) {
            restartInFlight.add(serverId)
            crashRestarts.trySend(serverId)
            log.info("Unexpected graceful stop for server {} (prev={}) — crash-restarting", serverId, prevStatus)
            return true
        }
        return false
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
            restartInFlight.remove(serverId)
            return
        }
        val crashed = newStatus == ServerStatus.UNHEALTHY &&
            (prevStatus == ServerStatus.HEALTHY || prevStatus == ServerStatus.STARTING)
        if (!crashed) return

        // A single unexpected death can emit several events (die watcher → UNHEALTHY or STOPPED,
        // console teardown → STOPPED). Restart once per death, not once per event.
        // UNHEALTHY after a dispatched restart (DB shows STARTING) is a fresh failure of that
        // restart — clear the flag and retry within the cap.
        if (restartInFlight.contains(serverId)) {
            if (prevStatus != ServerStatus.STARTING) return
            restartInFlight.remove(serverId)
        }
        if (mgr.recordCrashAndShouldRestart(serverId)) {
            restartInFlight.add(serverId)
            crashRestarts.trySend(serverId)
        }
    }

    private fun persistPlayerUpdate(event: AgentEvent.PlayerUpdateEvent) {
        val serverId = runCatching { Uuid.parse(event.serverId) }.getOrNull() ?: return
        val now = clock.now()
        val namesString = event.playerNames.joinToString(",")
            .takeIf { s -> s.isNotBlank() }

        transaction {
            val e = Server.findById(serverId) ?: return@transaction
            e.lastPlayerCount = event.playerCount
            e.lastPlayerNames = namesString
            e.lastPlayerUpdate = now.toLocalDateTime(TimeZone.UTC)
        }
    }

    private fun persistBackupComplete(event: AgentEvent.BackupCompleteEvent) {
        val backupId = runCatching { Uuid.parse(event.backupId) }.getOrNull() ?: return
        val status = if (event.success) BackupStatus.COMPLETED else BackupStatus.FAILED
        val sizeBytes = if (event.success) event.sizeBytes.takeIf { it > 0 } else null
        val errorMessage = if (!event.success) event.errorMessage.takeIf { it.isNotBlank() } else null

        transaction {
            Backup.findById(backupId)
                ?.let {
                    it.status = status.name
                    if (sizeBytes != null) it.sizeBytes = sizeBytes
                    if (errorMessage != null) it.errorMessage = errorMessage
                    if (event.completedAt != null) it.completedAt = event.completedAt.toLocalDateTime(TimeZone.UTC)
                }
        }
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
