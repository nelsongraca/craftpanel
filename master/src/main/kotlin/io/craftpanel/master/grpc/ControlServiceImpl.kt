package io.craftpanel.master.grpc

import com.craftpanel.agent.v1.*
import io.craftpanel.master.config.NodeConfig
import io.grpc.Status
import io.grpc.StatusException
import io.craftpanel.master.database.schema.*
import io.craftpanel.master.util.toKotlinUuid
import java.util.UUID
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.concurrent.atomic.AtomicReference
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

data class AlertEventNotification(
    val eventId: String,
    val thresholdId: String,
    val scopeType: String,
    val scopeId: String,
    val metric: String,
    val message: String,
    val firedAt: String,
    val resolvedAt: String? = null,
)

class ControlServiceImpl(private val nodeConfig: NodeConfig) :
    ControlServiceGrpcKt.ControlServiceCoroutineImplBase() {

    private val log = LoggerFactory.getLogger(ControlServiceImpl::class.java)
    private val random = SecureRandom()
    private val connectedAgents = ConcurrentHashMap<String, SendChannel<MasterMessage>>()

    private val _nodeMetricsFlow      = MutableSharedFlow<Pair<String, NodeMetricsUpdate>>(extraBufferCapacity = 256)
    private val _containerMetricsFlow = MutableSharedFlow<Pair<String, ContainerMetricsUpdate>>(extraBufferCapacity = 256)
    private val _serverStatusFlow     = MutableSharedFlow<Pair<String, ServerStatusUpdate>>(extraBufferCapacity = 256)
    private val _playerUpdateFlow     = MutableSharedFlow<Pair<String, PlayerUpdate>>(extraBufferCapacity = 256)
    private val _nodeStatusFlow       = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 64)
    private val _alertEventFlow       = MutableSharedFlow<AlertEventNotification>(extraBufferCapacity = 64)
    private val _backupProgressFlow   = MutableSharedFlow<BackupProgressUpdate>(extraBufferCapacity = 128)
    private val _backupCompleteFlow   = MutableSharedFlow<BackupCompleteUpdate>(extraBufferCapacity = 64)

    val nodeMetricsFlow      = _nodeMetricsFlow.asSharedFlow()
    val containerMetricsFlow = _containerMetricsFlow.asSharedFlow()
    val serverStatusFlow     = _serverStatusFlow.asSharedFlow()
    val playerUpdateFlow     = _playerUpdateFlow.asSharedFlow()
    val nodeStatusFlow       = _nodeStatusFlow.asSharedFlow()
    val alertEventFlow       = _alertEventFlow.asSharedFlow()
    val backupProgressFlow   = _backupProgressFlow.asSharedFlow()
    val backupCompleteFlow   = _backupCompleteFlow.asSharedFlow()

    fun sendToNode(nodeId: String, msg: MasterMessage): Boolean {
        val channel = connectedAgents[nodeId] ?: return false
        return channel.trySend(msg).isSuccess
    }

    override suspend fun registerNode(request: RegisterNodeRequest): RegisterNodeResponse {
        require(request.bootstrapToken == nodeConfig.bootstrapToken) {
            "Invalid bootstrap token"
        }

        val rawKey = generateNodeKey()
        val keyHash = sha256Hex(rawKey)
        val meta = request.metadata
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

        val generatedId = transaction {
            Nodes.insert {
                it[displayName] = meta.hostname
                it[hostname] = meta.hostname
                it[publicIp] = meta.publicIp
                it[privateIp] = meta.privateIp
                it[tokenHash] = keyHash
                it[status] = "PENDING"
                it[totalRamMb] = meta.totalRamMb
                it[totalCpuShares] = meta.totalCpuShares
                it[agentVersion] = meta.agentVersion.takeIf { it.isNotEmpty() }
                it[lastSeenAt] = now
            }[Nodes.id]
        }

        log.info("Node registered: $generatedId (${meta.hostname}) — status PENDING, awaiting admin approval")
        return registerNodeResponse {
            nodeKey = rawKey
            nodeId = generatedId.toString()
        }
    }

    override suspend fun identifyNode(request: IdentifyNodeRequest): IdentifyNodeResponse {
        val keyHash = sha256Hex(request.nodeKey)
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

        val row = transaction {
            val r = Nodes.selectAll()
                .where { Nodes.tokenHash eq keyHash }
                .firstOrNull()

            if (r != null) {
                Nodes.update({ Nodes.tokenHash eq keyHash }) {
                    it[lastSeenAt] = now
                    it[publicIp] = request.metadata.publicIp
                    it[privateIp] = request.metadata.privateIp
                    it[agentVersion] = request.metadata.agentVersion.takeIf { it.isNotEmpty() }
                }
            }
            r
        }

        val identifyStatus = when (row?.get(Nodes.status)) {
            "ACTIVE" -> IdentifyNodeResponse.IdentifyStatus.ACTIVE
            "PENDING" -> IdentifyNodeResponse.IdentifyStatus.PENDING
            else -> IdentifyNodeResponse.IdentifyStatus.REJECTED
        }

        val rowId = row?.get(Nodes.id)?.toString() ?: ""
        log.info("Node identified: $rowId — $identifyStatus")
        return identifyNodeResponse {
            status = identifyStatus
            nodeId = rowId
        }
    }

    override fun control(requests: Flow<AgentMessage>): Flow<MasterMessage> = channelFlow {
        var connectedNodeId: String? = null
        val outChannel = this.channel
        val lastMetricsAt = AtomicReference<Instant>(Clock.System.now())
        var watchdogFired = false

        launch {
            while (!watchdogFired) {
                delay(60.seconds)
                val elapsed = Clock.System.now() - lastMetricsAt.get()
                if (elapsed.inWholeSeconds > 120 && !watchdogFired) {
                    watchdogFired = true
                    connectedNodeId?.let { nodeId ->
                        log.warn("Node $nodeId: no metrics for ${elapsed.inWholeSeconds}s — marking degraded")
                        markNodeDegraded(nodeId)
                        _nodeStatusFlow.emit(nodeId to "DEGRADED")
                    }
                }
            }
        }

        try {
            requests.collect { msg ->
                if (connectedNodeId == null) {
                    val nodeId = msg.nodeId
                    val nodeStatus = transaction {
                        Nodes.selectAll()
                            .where { Nodes.id eq UUID.fromString(nodeId).toKotlinUuid() }
                            .firstOrNull()?.get(Nodes.status)
                    }
                    if (nodeStatus == null || nodeStatus == "REJECTED" || nodeStatus == "DECOMMISSIONED") {
                        throw StatusException(Status.PERMISSION_DENIED.withDescription("Node $nodeId is not authorized to connect"))
                    }
                    connectedNodeId = nodeId
                    connectedAgents[nodeId] = outChannel
                }

                when {
                    msg.hasNodeState() -> {
                        log.info("Node ${msg.nodeId} sent state snapshot with ${msg.nodeState.containersCount} containers")
                        reconcileNodeState(msg.nodeId, msg.nodeState)
                    }
                    msg.hasNodeMetrics() -> {
                        lastMetricsAt.set(Clock.System.now())
                        persistNodeMetrics(msg.nodeId, msg.nodeMetrics)
                        _nodeMetricsFlow.emit(msg.nodeId to msg.nodeMetrics)
                        launch { evaluateNodeAlerts(msg.nodeId, msg.nodeMetrics) }
                    }
                    msg.hasContainerMetrics() -> {
                        persistContainerMetrics(msg.containerMetrics)
                        _containerMetricsFlow.emit(msg.containerMetrics.serverId to msg.containerMetrics)
                        launch { evaluateServerAlerts(msg.containerMetrics) }
                    }
                    msg.hasServerStatus() -> {
                        log.debug("Node ${msg.nodeId} server status: ${msg.serverStatus.serverId} → ${msg.serverStatus.status}")
                        persistServerStatus(msg.serverStatus)
                        _serverStatusFlow.emit(msg.serverStatus.serverId to msg.serverStatus)
                    }
                    msg.hasPlayerUpdate() -> {
                        log.debug("Node ${msg.nodeId} player update: ${msg.playerUpdate.serverId} — ${msg.playerUpdate.playerCount} players")
                        _playerUpdateFlow.emit(msg.playerUpdate.serverId to msg.playerUpdate)
                    }
                    msg.hasBackupProgress() -> {
                        _backupProgressFlow.emit(msg.backupProgress)
                    }
                    msg.hasBackupComplete() -> {
                        persistBackupComplete(msg.backupComplete)
                        _backupCompleteFlow.emit(msg.backupComplete)
                    }
                    else -> log.debug("Node ${msg.nodeId} sent unhandled message type")
                }
            }
        } finally {
            connectedNodeId?.let { nodeId ->
                connectedAgents.remove(nodeId, outChannel)
                if (!watchdogFired) {
                    log.warn("Node $nodeId: control stream disconnected — marking degraded")
                    markNodeDegraded(nodeId)
                    _nodeStatusFlow.emit(nodeId to "DEGRADED")
                }
            }
        }
    }

    internal fun reconcileNodeState(nodeId: String, snapshot: NodeStateSnapshot) {
        val kotlinNodeId = runCatching { UUID.fromString(nodeId).toKotlinUuid() }.getOrNull() ?: return
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

        transaction {
            val currentStatus = Nodes.selectAll()
                .where { Nodes.id eq kotlinNodeId }
                .firstOrNull()?.get(Nodes.status)

            val byServerId = snapshot.containersList.associateBy { it.serverId }

            Servers.selectAll().where { Servers.nodeId eq kotlinNodeId }.forEach { server ->
                val serverId = server[Servers.id]
                val dbStatus = server[Servers.status]
                val container = byServerId[serverId.toString()]

                val newStatus: String? = when {
                    container == null ->
                        if (dbStatus in setOf("HEALTHY", "STARTING", "STOPPING", "UNHEALTHY")) "STOPPED" else null
                    container.runState == ContainerState.RunState.RUNNING && dbStatus != "HEALTHY" -> "HEALTHY"
                    container.runState == ContainerState.RunState.STOPPED &&
                            dbStatus in setOf("HEALTHY", "STARTING", "UNHEALTHY") -> "STOPPED"
                    container.runState == ContainerState.RunState.EXITED && dbStatus != "UNHEALTHY" -> "UNHEALTHY"
                    else -> null
                }

                if (newStatus != null) {
                    log.info("Node $nodeId reconcile: server $serverId $dbStatus → $newStatus")
                    Servers.update({ Servers.id eq serverId }) {
                        it[Servers.status] = newStatus
                        container?.containerId?.takeIf { s -> s.isNotEmpty() }?.let { cid ->
                            it[Servers.containerId] = cid
                        }
                        it[Servers.lastSeenAt] = now
                    }
                }
            }

            if (currentStatus == "DEGRADED") {
                Nodes.update({ Nodes.id eq kotlinNodeId }) {
                    it[Nodes.status] = "ACTIVE"
                    it[Nodes.lastSeenAt] = now
                }
            } else {
                Nodes.update({ Nodes.id eq kotlinNodeId }) {
                    it[Nodes.lastSeenAt] = now
                }
            }
        }
    }

    internal fun markNodeDegraded(nodeId: String) {
        val kotlinNodeId = runCatching { UUID.fromString(nodeId).toKotlinUuid() }.getOrNull() ?: return
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

        transaction {
            Nodes.update({ Nodes.id eq kotlinNodeId }) {
                it[Nodes.status] = "DEGRADED"
            }

            val serverCount = Servers.update({
                (Servers.nodeId eq kotlinNodeId) and
                        (Servers.status inList listOf("HEALTHY", "STARTING", "STOPPING"))
            }) {
                it[Servers.status] = "UNHEALTHY"
                it[Servers.lastSeenAt] = now
            }

            val migrationCount = ServerMigrations.update({
                ((ServerMigrations.sourceNodeId eq kotlinNodeId) or (ServerMigrations.targetNodeId eq kotlinNodeId)) and
                        (ServerMigrations.status inList listOf("PENDING", "RUNNING"))
            }) {
                it[ServerMigrations.status] = "FAILED"
                it[ServerMigrations.completedAt] = now
            }

            val backupCount = Backups.update({
                (Backups.nodeId eq kotlinNodeId) and (Backups.status eq "IN_PROGRESS")
            }) {
                it[Backups.status] = "FAILED"
                it[Backups.errorMessage] = "Node went offline during backup"
                it[Backups.completedAt] = now
            }

            log.warn("Node $nodeId marked DEGRADED: $serverCount servers → ERROR, $migrationCount migrations → FAILED, $backupCount backups → FAILED")
        }
    }

    private fun persistNodeMetrics(nodeId: String, metrics: NodeMetricsUpdate) {
        val kotlinNodeId = runCatching { UUID.fromString(nodeId).toKotlinUuid() }.getOrNull() ?: return
        val recordedAt = if (metrics.hasRecordedAt()) {
            Instant.fromEpochSeconds(metrics.recordedAt.seconds, metrics.recordedAt.nanos.toLong())
                .toLocalDateTime(TimeZone.UTC)
        } else {
            Clock.System.now().toLocalDateTime(TimeZone.UTC)
        }

        transaction {
            NodeMetrics.insert {
                it[NodeMetrics.nodeId] = kotlinNodeId
                it[NodeMetrics.recordedAt] = recordedAt
                it[NodeMetrics.cpuPercent] = metrics.cpuPercent
                it[NodeMetrics.ramUsedMb] = metrics.ramUsedMb
                it[NodeMetrics.ramTotalMb] = metrics.ramTotalMb
                it[NodeMetrics.netInBytes] = metrics.netInBytes
                it[NodeMetrics.netOutBytes] = metrics.netOutBytes
                it[NodeMetrics.diskUsedBytes] = metrics.diskUsedBytes
                it[NodeMetrics.diskTotalBytes] = metrics.diskTotalBytes
            }
        }
    }

    private fun persistContainerMetrics(metrics: ContainerMetricsUpdate) {
        val kotlinServerId = runCatching { UUID.fromString(metrics.serverId).toKotlinUuid() }.getOrNull() ?: return
        val recordedAt = if (metrics.hasRecordedAt()) {
            Instant.fromEpochSeconds(metrics.recordedAt.seconds, metrics.recordedAt.nanos.toLong())
                .toLocalDateTime(TimeZone.UTC)
        } else {
            Clock.System.now().toLocalDateTime(TimeZone.UTC)
        }

        transaction {
            ContainerMetrics.insert {
                it[ContainerMetrics.serverId] = kotlinServerId
                it[ContainerMetrics.recordedAt] = recordedAt
                it[ContainerMetrics.cpuPercent] = metrics.cpuPercent
                it[ContainerMetrics.ramUsedMb] = metrics.ramUsedMb
                it[ContainerMetrics.netInBytes] = metrics.netInBytes
                it[ContainerMetrics.netOutBytes] = metrics.netOutBytes
            }
        }
    }

    private fun persistServerStatus(update: ServerStatusUpdate) {
        val serverId = runCatching { UUID.fromString(update.serverId).toKotlinUuid() }.getOrNull() ?: return
        val dbStatus = when (update.status) {
            ServerStatusUpdate.ServerStatus.STARTING -> "STARTING"
            ServerStatusUpdate.ServerStatus.HEALTHY -> "HEALTHY"
            ServerStatusUpdate.ServerStatus.STOPPED -> "STOPPED"
            ServerStatusUpdate.ServerStatus.UNHEALTHY -> "UNHEALTHY"
            else -> return
        }
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        transaction {
            Servers.update({ Servers.id eq serverId }) {
                it[Servers.status] = dbStatus
                it[Servers.containerId] = update.containerId.takeIf { s -> s.isNotEmpty() }
                it[Servers.lastSeenAt] = now
            }
        }
    }

    private fun persistBackupComplete(update: BackupCompleteUpdate) {
        val backupId = runCatching { UUID.fromString(update.backupId).toKotlinUuid() }.getOrNull() ?: return
        val completedAt = if (update.hasCompletedAt()) {
            Instant.fromEpochSeconds(update.completedAt.seconds, update.completedAt.nanos.toLong())
                .toLocalDateTime(TimeZone.UTC)
        } else {
            Clock.System.now().toLocalDateTime(TimeZone.UTC)
        }

        transaction {
            Backups.update({ Backups.id eq backupId }) {
                if (update.success) {
                    it[Backups.status] = "COMPLETED"
                    it[Backups.filePath] = update.filePath.takeIf { s -> s.isNotEmpty() }
                    it[Backups.sizeBytes] = update.sizeBytes.takeIf { n -> n > 0 }
                } else {
                    it[Backups.status] = "FAILED"
                    it[Backups.errorMessage] = update.errorMessage.takeIf { s -> s.isNotEmpty() }
                }
                it[Backups.completedAt] = completedAt
            }
        }
    }

    private suspend fun evaluateNodeAlerts(nodeId: String, metrics: NodeMetricsUpdate) {
        val kotlinNodeId = runCatching { UUID.fromString(nodeId).toKotlinUuid() }.getOrNull() ?: return
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

        val metricValues = buildMap {
            put("cpu_percent", metrics.cpuPercent)
            if (metrics.ramTotalMb > 0)
                put("ram_percent", metrics.ramUsedMb.toDouble() / metrics.ramTotalMb * 100.0)
            if (metrics.diskTotalBytes > 0)
                put("disk_percent", metrics.diskUsedBytes.toDouble() / metrics.diskTotalBytes * 100.0)
        }

        val notifications = transaction {
            val result = mutableListOf<AlertEventNotification>()
            val thresholds = AlertThresholds.selectAll()
                .where { (AlertThresholds.scopeType eq "NODE") and (AlertThresholds.scopeId eq kotlinNodeId) and AlertThresholds.thresholdValue.isNotNull() }
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
                    val msg = "Node $nodeId: $metric at ${"%.1f".format(currentValue)}%"
                    val eventId = AlertEvents.insert {
                        it[AlertEvents.thresholdId] = thresholdId
                        it[AlertEvents.firedAt] = now
                        it[AlertEvents.message] = msg
                    }[AlertEvents.id]
                    result += AlertEventNotification(eventId.toString(), thresholdId.toString(), "NODE", nodeId, metric, msg, now.toString())
                } else if (!triggered && openEvent != null) {
                    val eventId = openEvent[AlertEvents.id]
                    AlertEvents.update({ AlertEvents.id eq eventId }) { it[AlertEvents.resolvedAt] = now }
                    val msg = "Node $nodeId: $metric normalised"
                    result += AlertEventNotification(eventId.toString(), thresholdId.toString(), "NODE", nodeId, metric, msg, openEvent[AlertEvents.firedAt].toString(), now.toString())
                }
            }
            result
        }

        notifications.forEach { _alertEventFlow.emit(it) }
    }

    private suspend fun evaluateServerAlerts(metrics: ContainerMetricsUpdate) {
        val kotlinServerId = runCatching { UUID.fromString(metrics.serverId).toKotlinUuid() }.getOrNull() ?: return
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

        val serverMemMb = transaction {
            Servers.selectAll().where { Servers.id eq kotlinServerId }.firstOrNull()?.get(Servers.memoryMb)
        } ?: return

        val metricValues = buildMap {
            put("cpu_percent", metrics.cpuPercent)
            if (serverMemMb > 0)
                put("ram_percent", metrics.ramUsedMb.toDouble() / serverMemMb * 100.0)
        }

        val notifications = transaction {
            val result = mutableListOf<AlertEventNotification>()
            val thresholds = AlertThresholds.selectAll()
                .where { (AlertThresholds.scopeType eq "SERVER") and (AlertThresholds.scopeId eq kotlinServerId) and AlertThresholds.thresholdValue.isNotNull() }
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
                    val msg = "Server ${metrics.serverId}: $metric at ${"%.1f".format(currentValue)}%"
                    val eventId = AlertEvents.insert {
                        it[AlertEvents.thresholdId] = thresholdId
                        it[AlertEvents.firedAt] = now
                        it[AlertEvents.message] = msg
                    }[AlertEvents.id]
                    result += AlertEventNotification(eventId.toString(), thresholdId.toString(), "SERVER", metrics.serverId, metric, msg, now.toString())
                } else if (!triggered && openEvent != null) {
                    val eventId = openEvent[AlertEvents.id]
                    AlertEvents.update({ AlertEvents.id eq eventId }) { it[AlertEvents.resolvedAt] = now }
                    val msg = "Server ${metrics.serverId}: $metric normalised"
                    result += AlertEventNotification(eventId.toString(), thresholdId.toString(), "SERVER", metrics.serverId, metric, msg, openEvent[AlertEvents.firedAt].toString(), now.toString())
                }
            }
            result
        }

        notifications.forEach { _alertEventFlow.emit(it) }
    }

    fun generateNodeKey(): String {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
