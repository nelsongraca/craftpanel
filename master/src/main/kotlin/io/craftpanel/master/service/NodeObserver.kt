package io.craftpanel.master.service

import io.craftpanel.master.auth.ScopeType
import io.craftpanel.master.database.schema.AlertEvents
import io.craftpanel.master.database.schema.AlertThresholds
import io.craftpanel.master.database.schema.Backups
import io.craftpanel.master.database.schema.ContainerMetrics
import io.craftpanel.master.database.schema.NodeMetrics
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.domain.NodeHealth
import io.craftpanel.master.domain.ServerStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import io.craftpanel.master.util.toUtcString
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
    private val restartServer: (Uuid) -> Unit,
    private val emitAgentEvent: suspend (AgentEvent) -> Unit,
    private val clock: Clock = Clock.System,
) {

    private val log = LoggerFactory.getLogger(NodeObserver::class.java)

    fun start(scope: CoroutineScope): Job = scope.launch {
        agentEvents.collect { event ->
            try {
                when (event) {
                    is AgentEvent.NodeMetricsEvent      -> {
                        persistNodeMetrics(event)
                        evaluateNodeAlerts(event)
                    }

                    is AgentEvent.ContainerMetricsEvent -> {
                        persistContainerMetrics(event)
                        evaluateServerAlerts(event)
                    }

                    is AgentEvent.ServerStatusEvent     -> persistServerStatus(event)
                    is AgentEvent.PlayerUpdateEvent     -> persistPlayerUpdate(event)
                    is AgentEvent.BackupCompleteEvent   -> persistBackupComplete(event)
                    else                                -> { /* unrelated events */
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
        val recordedAt = event.recordedAt.toLocalDateTime(TimeZone.UTC)

        transaction {
            NodeMetrics.insert {
                it[NodeMetrics.nodeId] = kotlinNodeId
                it[NodeMetrics.recordedAt] = recordedAt
                it[NodeMetrics.cpuPercent] = event.cpuPercent
                it[NodeMetrics.ramUsedMb] = event.ramUsedMb
                it[NodeMetrics.ramTotalMb] = event.ramTotalMb
                it[NodeMetrics.netInBytes] = event.netInBytes
                it[NodeMetrics.netOutBytes] = event.netOutBytes
                it[NodeMetrics.diskUsedBytes] = event.diskUsedBytes
                it[NodeMetrics.diskTotalBytes] = event.diskTotalBytes
            }
            if (event.ramUsedMb > 0) {
                Nodes.update({ Nodes.id eq kotlinNodeId }) {
                    it[Nodes.systemRamUsedMb] = event.ramUsedMb
                }
            }
        }
    }

    private fun persistContainerMetrics(event: AgentEvent.ContainerMetricsEvent) {
        val kotlinServerId = runCatching { Uuid.parse(event.serverId) }.getOrNull() ?: return
        val recordedAt = event.recordedAt.toLocalDateTime(TimeZone.UTC)

        transaction {
            ContainerMetrics.insert {
                it[ContainerMetrics.serverId] = kotlinServerId
                it[ContainerMetrics.recordedAt] = recordedAt
                it[ContainerMetrics.cpuPercent] = event.cpuPercent
                it[ContainerMetrics.ramUsedMb] = event.ramUsedMb
                it[ContainerMetrics.netInBytes] = event.netInBytes
                it[ContainerMetrics.netOutBytes] = event.netOutBytes
                it[ContainerMetrics.blockInBytes] = event.blockInBytes
                it[ContainerMetrics.blockOutBytes] = event.blockOutBytes
            }
        }
    }

    private fun persistServerStatus(event: AgentEvent.ServerStatusEvent) {
        val serverId = runCatching { Uuid.parse(event.serverId) }.getOrNull() ?: return
        val now = clock.now()
            .toLocalDateTime(TimeZone.UTC)
        val prevStatus = transaction {
            val prev = Servers.selectAll()
                .where { Servers.id eq serverId }
                .firstOrNull()
                ?.let { ServerStatus.fromDb(it[Servers.status]) }
            Servers.update({ Servers.id eq serverId }) {
                it[Servers.status] = event.status.toDb()
                it[Servers.lastSeenAt] = now
            }
            prev
        }
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
            runCatching { restartServer(serverId) }
                .onFailure { e -> log.error("Crash restart for server {} failed to dispatch — {}", serverId, e.message) }
        }
    }

    private fun persistPlayerUpdate(event: AgentEvent.PlayerUpdateEvent) {
        val serverId = runCatching { Uuid.parse(event.serverId) }.getOrNull() ?: return
        val now = clock.now()
            .toLocalDateTime(TimeZone.UTC)
        transaction {
            Servers.update({ Servers.id eq serverId }) {
                it[Servers.lastPlayerCount] = event.playerCount
                it[Servers.lastPlayerNames] = event.playerNames.joinToString(",")
                    .takeIf { s -> s.isNotBlank() }
                it[Servers.lastPlayerUpdate] = now
            }
        }
    }

    private fun persistBackupComplete(event: AgentEvent.BackupCompleteEvent) {
        val backupId = runCatching { Uuid.parse(event.backupId) }.getOrNull() ?: return
        val completedAt = event.completedAt.toLocalDateTime(TimeZone.UTC)

        transaction {
            Backups.update({ Backups.id eq backupId }) {
                if (event.success) {
                    it[Backups.status] = "COMPLETED"
                    it[Backups.sizeBytes] = event.sizeBytes.takeIf { n -> n > 0 }
                }
                else {
                    it[Backups.status] = "FAILED"
                    it[Backups.errorMessage] = event.errorMessage.takeIf { s -> s.isNotBlank() }
                }
                it[Backups.completedAt] = completedAt
            }
        }
    }

    // ── Alert evaluation ──────────────────────────────────────────────────────

    private suspend fun evaluateNodeAlerts(event: AgentEvent.NodeMetricsEvent) {
        val kotlinNodeId = runCatching { Uuid.parse(event.nodeId) }.getOrNull() ?: return
        val now = clock.now()
            .toLocalDateTime(TimeZone.UTC)

        val metricValues = buildMap {
            put("cpu_percent", event.cpuPercent)
            if (event.ramTotalMb > 0)
                put("ram_percent", event.ramUsedMb.toDouble() / event.ramTotalMb * 100.0)
            if (event.diskTotalBytes > 0)
                put("disk_percent", event.diskUsedBytes.toDouble() / event.diskTotalBytes * 100.0)
        }

        val notifications = transaction {
            val result = mutableListOf<AgentEvent.AlertFiredEvent>()
            val thresholds = AlertThresholds.selectAll()
                .where { (AlertThresholds.scopeType eq ScopeType.NODE.name) and (AlertThresholds.scopeId eq kotlinNodeId) and AlertThresholds.thresholdValue.isNotNull() }
                .toList()

            for (threshold in thresholds) {
                val thresholdId = threshold[AlertThresholds.id]
                val metric = threshold[AlertThresholds.metric]
                val limitValue = threshold[AlertThresholds.thresholdValue] ?: continue
                val currentValue = metricValues[metric] ?: continue
                val triggered = currentValue > limitValue
                val openEvent = AlertEvents.selectAll()
                    .where { (AlertEvents.thresholdId eq thresholdId) and AlertEvents.resolvedAt.isNull() }
                    .firstOrNull()

                if (triggered && openEvent == null) {
                    val msg = "Node ${event.nodeId}: $metric at ${"%.1f".format(currentValue)}%"
                    val newEventId = AlertEvents.insert {
                        it[AlertEvents.thresholdId] = thresholdId
                        it[AlertEvents.firedAt] = now
                        it[AlertEvents.message] = msg
                    }[AlertEvents.id]
                    result += AgentEvent.AlertFiredEvent(
                        eventId = newEventId.toString(),
                        thresholdId = thresholdId.toString(),
                        scopeType = ScopeType.NODE.name,
                        scopeId = event.nodeId,
                        metric = metric,
                        message = msg,
                        firedAt = now.toUtcString(),
                        resolvedAt = null,
                    )
                }
                else if (!triggered && openEvent != null) {
                    val eventId = openEvent[AlertEvents.id]
                    AlertEvents.update({ AlertEvents.id eq eventId }) { it[AlertEvents.resolvedAt] = now }
                    val msg = "Node ${event.nodeId}: $metric normalised"
                    result += AgentEvent.AlertFiredEvent(
                        eventId = eventId.toString(),
                        thresholdId = thresholdId.toString(),
                        scopeType = ScopeType.NODE.name,
                        scopeId = event.nodeId,
                        metric = metric,
                        message = msg,
                        firedAt = openEvent[AlertEvents.firedAt].toUtcString(),
                        resolvedAt = now.toUtcString(),
                    )
                }
            }
            result
        }

        notifications.forEach { emitAgentEvent(it) }
    }

    private suspend fun evaluateServerAlerts(event: AgentEvent.ContainerMetricsEvent) {
        val kotlinServerId = runCatching { Uuid.parse(event.serverId) }.getOrNull() ?: return
        val now = clock.now()
            .toLocalDateTime(TimeZone.UTC)

        val serverMemMb = transaction {
            Servers.selectAll()
                .where { Servers.id eq kotlinServerId }
                .firstOrNull()
                ?.get(Servers.memoryMb)
        } ?: return

        val metricValues = buildMap {
            put("cpu_percent", event.cpuPercent)
            if (serverMemMb > 0)
                put("ram_percent", event.ramUsedMb.toDouble() / serverMemMb * 100.0)
        }

        val notifications = transaction {
            val result = mutableListOf<AgentEvent.AlertFiredEvent>()
            val thresholds = AlertThresholds.selectAll()
                .where { (AlertThresholds.scopeType eq ScopeType.SERVER.name) and (AlertThresholds.scopeId eq kotlinServerId) and AlertThresholds.thresholdValue.isNotNull() }
                .toList()

            for (threshold in thresholds) {
                val thresholdId = threshold[AlertThresholds.id]
                val metric = threshold[AlertThresholds.metric]
                val limitValue = threshold[AlertThresholds.thresholdValue] ?: continue
                val currentValue = metricValues[metric] ?: continue
                val triggered = currentValue > limitValue
                val openEvent = AlertEvents.selectAll()
                    .where { (AlertEvents.thresholdId eq thresholdId) and AlertEvents.resolvedAt.isNull() }
                    .firstOrNull()

                if (triggered && openEvent == null) {
                    val msg = "Server ${event.serverId}: $metric at ${"%.1f".format(currentValue)}%"
                    val newEventId = AlertEvents.insert {
                        it[AlertEvents.thresholdId] = thresholdId
                        it[AlertEvents.firedAt] = now
                        it[AlertEvents.message] = msg
                    }[AlertEvents.id]
                    result += AgentEvent.AlertFiredEvent(
                        eventId = newEventId.toString(),
                        thresholdId = thresholdId.toString(),
                        scopeType = ScopeType.SERVER.name,
                        scopeId = event.serverId,
                        metric = metric,
                        message = msg,
                        firedAt = now.toUtcString(),
                        resolvedAt = null,
                    )
                }
                else if (!triggered && openEvent != null) {
                    val eventId = openEvent[AlertEvents.id]
                    AlertEvents.update({ AlertEvents.id eq eventId }) { it[AlertEvents.resolvedAt] = now }
                    val msg = "Server ${event.serverId}: $metric normalised"
                    result += AgentEvent.AlertFiredEvent(
                        eventId = eventId.toString(),
                        thresholdId = thresholdId.toString(),
                        scopeType = ScopeType.SERVER.name,
                        scopeId = event.serverId,
                        metric = metric,
                        message = msg,
                        firedAt = openEvent[AlertEvents.firedAt].toUtcString(),
                        resolvedAt = now.toUtcString(),
                    )
                }
            }
            result
        }

        notifications.forEach { emitAgentEvent(it) }
    }
}
