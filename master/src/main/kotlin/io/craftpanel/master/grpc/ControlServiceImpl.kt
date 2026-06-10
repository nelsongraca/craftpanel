package io.craftpanel.master.grpc

import io.craftpanel.master.auth.ScopeType
import com.craftpanel.agent.v1.*
import io.craftpanel.master.config.NodeConfig
import io.craftpanel.master.database.schema.*
import io.craftpanel.master.util.toKotlinUuid
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import io.craftpanel.master.util.toUtcString

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

class ControlServiceImpl(
    private val nodeConfig: NodeConfig,
    private val onNodeDisconnect: (String) -> Unit = {},
) : ControlServiceGrpcKt.ControlServiceCoroutineImplBase() {

    private val log = LoggerFactory.getLogger(ControlServiceImpl::class.java)
    private val random = SecureRandom()
    private val connectedAgents = ConcurrentHashMap<String, SendChannel<MasterMessage>>()

    // ── Data op correlation (keyed by "$nodeId/$requestId") ───────────────────
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<AgentMessage>>()
    private val consoleOutputChannels = ConcurrentHashMap<String, Channel<ConsoleOutput>>()

    // ── Observability flows ───────────────────────────────────────────────────
    private val _nodeMetricsFlow = MutableSharedFlow<Pair<String, NodeMetricsUpdate>>(extraBufferCapacity = 256)
    private val _containerMetricsFlow = MutableSharedFlow<Pair<String, ContainerMetricsUpdate>>(extraBufferCapacity = 256)
    private val _serverStatusFlow = MutableSharedFlow<Pair<String, ServerStatusUpdate>>(extraBufferCapacity = 256)
    private val _playerUpdateFlow = MutableSharedFlow<Pair<String, PlayerUpdate>>(extraBufferCapacity = 256)
    private val _nodeStatusFlow = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 64)
    private val _alertEventFlow = MutableSharedFlow<AlertEventNotification>(extraBufferCapacity = 64)
    private val _backupProgressFlow = MutableSharedFlow<BackupProgressUpdate>(extraBufferCapacity = 128)
    private val _backupCompleteFlow = MutableSharedFlow<BackupCompleteUpdate>(extraBufferCapacity = 64)
    private val _rsyncReadyFlow = MutableSharedFlow<RsyncReadyUpdate>(extraBufferCapacity = 64)
    private val _rsyncProgressFlow = MutableSharedFlow<RsyncProgressUpdate>(extraBufferCapacity = 256)
    private val _rsyncCompleteFlow = MutableSharedFlow<RsyncCompleteUpdate>(extraBufferCapacity = 64)

    val nodeMetricsFlow = _nodeMetricsFlow.asSharedFlow()
    val containerMetricsFlow = _containerMetricsFlow.asSharedFlow()
    val serverStatusFlow = _serverStatusFlow.asSharedFlow()
    val playerUpdateFlow = _playerUpdateFlow.asSharedFlow()
    val nodeStatusFlow = _nodeStatusFlow.asSharedFlow()
    val alertEventFlow = _alertEventFlow.asSharedFlow()
    val backupProgressFlow = _backupProgressFlow.asSharedFlow()
    val backupCompleteFlow = _backupCompleteFlow.asSharedFlow()
    val rsyncReadyFlow = _rsyncReadyFlow.asSharedFlow()
    val rsyncProgressFlow = _rsyncProgressFlow.asSharedFlow()
    val rsyncCompleteFlow = _rsyncCompleteFlow.asSharedFlow()

    fun sendToNode(nodeId: String, msg: MasterMessage): Boolean {
        val channel = connectedAgents[nodeId]
        if (channel == null) {
            log.warn("sendToNode: node {} not found in connectedAgents (connected: {})", nodeId, connectedAgents.keys)
            return false
        }
        return channel.trySend(msg).isSuccess
    }

    // ── gRPC: registration / identification ──────────────────────────────────

    override suspend fun registerNode(request: RegisterNodeRequest): RegisterNodeResponse {
        require(
            MessageDigest.isEqual(
                request.bootstrapToken.toByteArray(Charsets.UTF_8),
                nodeConfig.bootstrapToken.toByteArray(Charsets.UTF_8),
            )
        ) { "Invalid bootstrap token" }

        val rawKey = generateNodeKey()
        val keyHash = sha256Hex(rawKey)
        val meta = request.metadata
        val now = Clock.System.now()
            .toLocalDateTime(TimeZone.UTC)

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
                it[agentVersion] = meta.agentVersion.takeIf { v -> v.isNotEmpty() }
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
        val now = Clock.System.now()
            .toLocalDateTime(TimeZone.UTC)

        val row = transaction {
            val r = Nodes.selectAll()
                .where { Nodes.tokenHash eq keyHash }
                .firstOrNull()

            if (r != null) {
                Nodes.update({ Nodes.tokenHash eq keyHash }) {
                    it[lastSeenAt] = now
                    it[publicIp] = request.metadata.publicIp
                    it[privateIp] = request.metadata.privateIp
                    it[agentVersion] = request.metadata.agentVersion.takeIf { v -> v.isNotEmpty() }
                }
            }
            r
        }

        val identifyStatus = when (row?.get(Nodes.status)) {
            "ACTIVE", "DEGRADED" -> IdentifyNodeResponse.IdentifyStatus.ACTIVE
            "PENDING"            -> IdentifyNodeResponse.IdentifyStatus.PENDING
            else                 -> IdentifyNodeResponse.IdentifyStatus.REJECTED
        }

        val rowId = row?.get(Nodes.id)
            ?.toString() ?: ""
        log.info("Node identified: $rowId — $identifyStatus")
        return identifyNodeResponse {
            status = identifyStatus
            nodeId = rowId
        }
    }

    // ── gRPC: control stream ─────────────────────────────────────────────────

    override fun control(requests: Flow<AgentMessage>): Flow<MasterMessage> = channelFlow {
        log.info("control stream opened")
        var connectedNodeId: String? = null
        val outChannel = this.channel
        val lastMetricsAt = AtomicReference(Clock.System.now())
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
                log.info("control stream msg: nodeId=${msg.nodeId}, hasNodeState=${msg.hasNodeState()}, hasNodeMetrics=${msg.hasNodeMetrics()}")
                if (connectedNodeId == null) {
                    val nodeId = msg.nodeId
                    val nodeStatus = transaction {
                        Nodes.selectAll()
                            .where {
                                Nodes.id eq UUID.fromString(nodeId)
                                    .toKotlinUuid()
                            }
                            .firstOrNull()
                            ?.get(Nodes.status)
                    }
                    log.info("Node $nodeId: first message, db status=$nodeStatus")
                    if (nodeStatus !in setOf("ACTIVE", "DEGRADED")) {
                        val reason = when (nodeStatus) {
                            "PENDING"        -> "Node $nodeId is pending admin approval"
                            "REJECTED"       -> "Node $nodeId has been rejected"
                            "DECOMMISSIONED" -> "Node $nodeId has been decommissioned"
                            else             -> "Node $nodeId is not authorized to connect"
                        }
                        throw StatusException(Status.PERMISSION_DENIED.withDescription(reason))
                    }
                    connectedNodeId = nodeId
                    connectedAgents[nodeId] = outChannel
                    log.debug("Node $nodeId: registered in connectedAgents (channel=${System.identityHashCode(outChannel)})")
                }

                when {
                    msg.hasNodeState()             -> {
                        log.info("Node ${msg.nodeId} sent state snapshot with ${msg.nodeState.containersCount} containers")
                        runCatching { reconcileNodeState(msg.nodeId, msg.nodeState) }
                            .onSuccess { result ->
                                if (result != null) {
                                    log.debug("Node ${msg.nodeId}: reconcileNodeState ok, wasRestored=$result — emitting ACTIVE")
                                    _nodeStatusFlow.emit(msg.nodeId to "ACTIVE")
                                }
                                else {
                                    log.debug("Node ${msg.nodeId}: reconcileNodeState ok but node is PENDING — skipping ACTIVE emit")
                                }
                            }
                            .onFailure { e -> log.error("Node ${msg.nodeId}: reconcileNodeState failed — ${e.message}", e) }
                    }

                    msg.hasNodeMetrics()           -> {
                        lastMetricsAt.set(Clock.System.now())
                        runCatching { persistNodeMetrics(msg.nodeId, msg.nodeMetrics) }
                            .onFailure { e -> log.error("Node ${msg.nodeId}: persistNodeMetrics failed — ${e.message}", e) }
                        _nodeMetricsFlow.emit(msg.nodeId to msg.nodeMetrics)
                        launch {
                            try {
                                evaluateNodeAlerts(msg.nodeId, msg.nodeMetrics)
                            }
                            catch (e: Exception) {
                                if (e is CancellationException) throw e
                                log.error("Node ${msg.nodeId}: evaluateNodeAlerts failed — ${e.message}", e)
                            }
                        }
                    }

                    msg.hasContainerMetrics()      -> {
                        runCatching { persistContainerMetrics(msg.containerMetrics) }
                            .onFailure { e -> log.error("Node ${msg.nodeId}: persistContainerMetrics failed — ${e.message}", e) }
                        _containerMetricsFlow.emit(msg.containerMetrics.serverId to msg.containerMetrics)
                        launch {
                            try {
                                evaluateServerAlerts(msg.containerMetrics)
                            }
                            catch (e: Exception) {
                                if (e is CancellationException) throw e
                                log.error("Node ${msg.nodeId}: evaluateServerAlerts failed — ${e.message}", e)
                            }
                        }
                    }

                    msg.hasServerStatus()          -> {
                        runCatching { persistServerStatus(msg.serverStatus) }
                            .onFailure { e -> log.error("Node ${msg.nodeId}: persistServerStatus failed — ${e.message}", e) }
                        _serverStatusFlow.emit(msg.serverStatus.serverId to msg.serverStatus)
                    }

                    msg.hasPlayerUpdate()          -> {
                        runCatching { persistPlayerUpdate(msg.playerUpdate) }
                            .onFailure { e -> log.error("Node ${msg.nodeId}: persistPlayerUpdate failed — ${e.message}", e) }
                        _playerUpdateFlow.emit(msg.playerUpdate.serverId to msg.playerUpdate)
                    }

                    msg.hasBackupProgress()        -> _backupProgressFlow.emit(msg.backupProgress)

                    msg.hasBackupComplete()        -> {
                        runCatching { persistBackupComplete(msg.backupComplete) }
                            .onFailure { e -> log.error("Node ${msg.nodeId}: persistBackupComplete failed — ${e.message}", e) }
                        _backupCompleteFlow.emit(msg.backupComplete)
                    }

                    msg.hasRsyncReady()            -> _rsyncReadyFlow.emit(msg.rsyncReady)
                    msg.hasRsyncProgress()         -> _rsyncProgressFlow.emit(msg.rsyncProgress)
                    msg.hasRsyncComplete()         -> _rsyncCompleteFlow.emit(msg.rsyncComplete)

                    // Data op responses — route to waiting callers
                    msg.hasConsoleOutput()         -> routeConsoleOutput(msg.nodeId, msg.consoleOutput)
                    msg.hasListFilesResponse()     -> routeUnaryResponse(msg.nodeId, msg.listFilesResponse.requestId, msg)
                    msg.hasReadFileResponse()      -> routeUnaryResponse(msg.nodeId, msg.readFileResponse.requestId, msg)
                    msg.hasWriteFileResponse()     -> routeUnaryResponse(msg.nodeId, msg.writeFileResponse.requestId, msg)
                    msg.hasDeleteFileResponse()    -> routeUnaryResponse(msg.nodeId, msg.deleteFileResponse.requestId, msg)
                    msg.hasMakeDirectoryResponse() -> routeUnaryResponse(msg.nodeId, msg.makeDirectoryResponse.requestId, msg)
                    msg.hasMoveFileResponse()      -> routeUnaryResponse(msg.nodeId, msg.moveFileResponse.requestId, msg)
                    msg.hasCopyFileResponse()      -> routeUnaryResponse(msg.nodeId, msg.copyFileResponse.requestId, msg)
                    msg.hasDownloadFileResponse()  -> routeUnaryResponse(msg.nodeId, msg.downloadFileResponse.requestId, msg)
                    msg.hasUploadFileResponse()    -> routeUnaryResponse(msg.nodeId, msg.uploadFileResponse.requestId, msg)

                    else                           -> log.debug("Node ${msg.nodeId} sent unhandled message type")
                }
            }
        }
        finally {
            connectedNodeId?.let { nodeId ->
                val wasOwner = connectedAgents.remove(nodeId, outChannel)
                log.debug(
                    "Node $nodeId: stream finally — wasOwner=$wasOwner, watchdogFired=$watchdogFired, channel=${System.identityHashCode(outChannel)}, stillConnected=${
                        connectedAgents.containsKey(
                            nodeId
                        )
                    }"
                )
                drainNodeRequests(nodeId)
                onNodeDisconnect(nodeId)
                if (wasOwner && !watchdogFired && !connectedAgents.containsKey(nodeId)) {
                    log.warn("Node $nodeId: control stream disconnected — marking degraded")
                    markNodeDegraded(nodeId)
                    _nodeStatusFlow.emit(nodeId to "DEGRADED")
                }
                else if (wasOwner && connectedAgents.containsKey(nodeId)) {
                    log.info("Node $nodeId: stream ended but new connection is already active — skipping degrade")
                }
                else if (!wasOwner) {
                    log.debug("Node $nodeId: stream finally skipped — not owner (superseded by newer connection)")
                }
            }
        }
    }

    // ── Data op routing helpers ───────────────────────────────────────────────

    private fun routeUnaryResponse(nodeId: String, requestId: String, msg: AgentMessage) {
        pendingRequests.remove("$nodeId/$requestId")
            ?.complete(msg)
    }

    private fun routeConsoleOutput(nodeId: String, output: ConsoleOutput) {
        val key = "$nodeId/${output.requestId}"
        consoleOutputChannels[key]?.trySend(output)
        if (output.closed) {
            consoleOutputChannels.remove(key)
                ?.close()
        }
    }

    private fun drainNodeRequests(nodeId: String) {
        val prefix = "$nodeId/"
        pendingRequests.entries.removeIf { (k, v) ->
            if (k.startsWith(prefix)) {
                v.completeExceptionally(Exception("Node $nodeId disconnected"))
                true
            }
            else false
        }
        consoleOutputChannels.entries.removeIf { (k, v) ->
            if (k.startsWith(prefix)) {
                v.close(Exception("Node $nodeId disconnected"))
                true
            }
            else false
        }
    }

    // ── Public data op methods (called by DataServiceProxy) ──────────────────

    /** Send a MasterMessage and wait for the agent's AgentMessage response. */
    internal suspend fun sendAndAwait(nodeId: String, reqId: String, msg: MasterMessage, timeoutMs: Long = 30_000): AgentMessage {
        val deferred = CompletableDeferred<AgentMessage>()
        pendingRequests["$nodeId/$reqId"] = deferred
        if (!sendToNode(nodeId, msg)) {
            pendingRequests.remove("$nodeId/$reqId")
            error("Node $nodeId is not connected")
        }
        return try {
            withTimeout(timeoutMs.milliseconds) { deferred.await() }
        }
        finally {
            pendingRequests.remove("$nodeId/$reqId")
        }
    }

    /** Open a multiplexed console session over the control stream. */
    internal fun openConsole(nodeId: String, serverId: String, input: Flow<ByteArray>): Flow<ConsoleOutput> =
        channelFlow {
            val reqId = UUID.randomUUID()
                .toString()
            val outputChannel = Channel<ConsoleOutput>(Channel.BUFFERED)
            consoleOutputChannels["$nodeId/$reqId"] = outputChannel

            if (!sendToNode(nodeId, masterMessage {
                    consoleAttach = consoleAttach { requestId = reqId; this.serverId = serverId }
                })) {
                consoleOutputChannels.remove("$nodeId/$reqId")
                error("Node $nodeId is not connected")
            }

            // Forward browser input to agent
            launch {
                try {
                    input.collect { bytes ->
                        sendToNode(nodeId, masterMessage {
                            consoleInput = consoleInput { requestId = reqId; data = com.google.protobuf.ByteString.copyFrom(bytes) }
                        })
                    }
                }
                finally {
                    sendToNode(nodeId, masterMessage {
                        consoleDetach = consoleDetach { requestId = reqId }
                    })
                }
            }

            // Forward agent output to caller
            try {
                for (output in outputChannel) {
                    send(output)
                    if (output.closed) break
                }
            }
            finally {
                consoleOutputChannels.remove("$nodeId/$reqId")
                    ?.close()
            }
        }

    /** Verify a node key from a bulk transfer auth header against the DB. */
    internal fun verifyNodeKey(rawNodeKey: String): Boolean = transaction {
        val hash = sha256Hex(rawNodeKey)
        Nodes.selectAll()
            .where { Nodes.tokenHash eq hash }
            .firstOrNull()
            ?.get(Nodes.status) == "ACTIVE"
    }

    // ── Reconciliation & lifecycle ────────────────────────────────────────────

    internal fun reconcileNodeState(nodeId: String, snapshot: NodeStateSnapshot): Boolean? {
        val kotlinNodeId = runCatching {
            UUID.fromString(nodeId)
                .toKotlinUuid()
        }.getOrNull() ?: return null
        val now = Clock.System.now()
            .toLocalDateTime(TimeZone.UTC)
        var wasRestored: Boolean? = null

        transaction {
            val currentStatus = Nodes.selectAll()
                .where { Nodes.id eq kotlinNodeId }
                .firstOrNull()
                ?.get(Nodes.status)

            log.debug("Node $nodeId: reconcileNodeState — currentStatus=$currentStatus, containers=${snapshot.containersCount}")
            val byServerId = snapshot.containersList.associateBy { it.serverId }

            Servers.selectAll()
                .where { Servers.nodeId eq kotlinNodeId }
                .forEach { server ->
                    val serverId = server[Servers.id]
                    val dbStatus = server[Servers.status]
                    val container = byServerId[serverId.toString()]

                    val newStatus: String? = when {
                        container == null                                                               ->
                            if (dbStatus in setOf("HEALTHY", "STARTING", "STOPPING", "UNHEALTHY")) "STOPPED" else null

                        container.runState == ContainerState.RunState.RUNNING && dbStatus != "HEALTHY"  -> "HEALTHY"
                        container.runState == ContainerState.RunState.STOPPED &&
                                dbStatus in setOf("HEALTHY", "STARTING", "UNHEALTHY")                   -> "STOPPED"

                        container.runState == ContainerState.RunState.EXITED && dbStatus != "UNHEALTHY" -> "UNHEALTHY"
                        else                                                                            -> null
                    }

                    if (newStatus != null) {
                        log.info("Node $nodeId reconcile: server $serverId $dbStatus → $newStatus")
                        Servers.update({ Servers.id eq serverId }) {
                            it[Servers.status] = newStatus
                            container?.containerId?.takeIf { s -> s.isNotEmpty() }
                                ?.let { cid -> it[Servers.containerId] = cid }
                            it[Servers.lastSeenAt] = now
                        }
                    }
                }

            if (currentStatus in setOf("ACTIVE", "DEGRADED")) {
                Nodes.update({ Nodes.id eq kotlinNodeId }) {
                    it[Nodes.status] = "ACTIVE"
                    it[Nodes.lastSeenAt] = now
                }
                wasRestored = currentStatus == "DEGRADED"
                log.debug("Node $nodeId: wrote status=ACTIVE (was $currentStatus), wasRestored=$wasRestored")
            }
            else {
                log.debug("Node $nodeId: status=$currentStatus — only updating lastSeenAt, not emitting ACTIVE")
                Nodes.update({ Nodes.id eq kotlinNodeId }) { it[Nodes.lastSeenAt] = now }
            }
        }
        return wasRestored
    }

    internal fun markNodeDegraded(nodeId: String) {
        val kotlinNodeId = runCatching {
            UUID.fromString(nodeId)
                .toKotlinUuid()
        }.getOrElse {
            log.warn("markNodeDegraded: invalid nodeId format: $nodeId")
            return
        }
        val now = Clock.System.now()
            .toLocalDateTime(TimeZone.UTC)

        transaction {
            val updated = Nodes.update({
                (Nodes.id eq kotlinNodeId) and (Nodes.status inList listOf("ACTIVE", "DEGRADED"))
            }) { it[Nodes.status] = "DEGRADED" }

            if (updated == 0) {
                log.debug("markNodeDegraded: node $nodeId is not ACTIVE/DEGRADED — skipping degrade")
                return@transaction
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
                        (ServerMigrations.status inList listOf("PENDING", "SYNCING", "CUTTING_OVER"))
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

    // ── Metrics persistence ───────────────────────────────────────────────────

    private fun persistNodeMetrics(nodeId: String, metrics: NodeMetricsUpdate) {
        val kotlinNodeId = runCatching {
            UUID.fromString(nodeId)
                .toKotlinUuid()
        }.getOrNull() ?: return
        val recordedAt = if (metrics.hasRecordedAt()) {
            Instant.fromEpochSeconds(metrics.recordedAt.seconds, metrics.recordedAt.nanos.toLong())
                .toLocalDateTime(TimeZone.UTC)
        }
        else {
            Clock.System.now()
                .toLocalDateTime(TimeZone.UTC)
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
            if (metrics.ramUsedMb > 0) {
                Nodes.update({ Nodes.id eq kotlinNodeId }) {
                    it[Nodes.systemRamUsedMb] = metrics.ramUsedMb
                }
            }
        }
    }

    private fun persistContainerMetrics(metrics: ContainerMetricsUpdate) {
        val kotlinServerId = runCatching {
            UUID.fromString(metrics.serverId)
                .toKotlinUuid()
        }.getOrNull() ?: return
        val recordedAt = if (metrics.hasRecordedAt()) {
            Instant.fromEpochSeconds(metrics.recordedAt.seconds, metrics.recordedAt.nanos.toLong())
                .toLocalDateTime(TimeZone.UTC)
        }
        else {
            Clock.System.now()
                .toLocalDateTime(TimeZone.UTC)
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
        val serverId = runCatching {
            UUID.fromString(update.serverId)
                .toKotlinUuid()
        }.getOrNull() ?: return
        val dbStatus = when (update.status) {
            ServerStatusUpdate.ServerStatus.STARTING  -> "STARTING"
            ServerStatusUpdate.ServerStatus.HEALTHY   -> "HEALTHY"
            ServerStatusUpdate.ServerStatus.STOPPED   -> "STOPPED"
            ServerStatusUpdate.ServerStatus.UNHEALTHY -> "UNHEALTHY"
            else                                      -> return
        }
        val now = Clock.System.now()
            .toLocalDateTime(TimeZone.UTC)
        transaction {
            Servers.update({ Servers.id eq serverId }) {
                it[Servers.status] = dbStatus
                it[Servers.containerId] = update.containerId.takeIf { s -> s.isNotEmpty() }
                it[Servers.lastSeenAt] = now
            }
        }
    }

    private fun persistPlayerUpdate(update: PlayerUpdate) {
        val serverId = runCatching {
            UUID.fromString(update.serverId)
                .toKotlinUuid()
        }.getOrNull() ?: return
        val now = Clock.System.now()
            .toLocalDateTime(TimeZone.UTC)
        transaction {
            Servers.update({ Servers.id eq serverId }) {
                it[Servers.lastPlayerCount] = update.playerCount
                it[Servers.lastPlayerNames] = update.playerNamesList.joinToString(",")
                    .takeIf { s -> s.isNotBlank() }
                it[Servers.lastPlayerUpdate] = now
            }
        }
    }

    private fun persistBackupComplete(update: BackupCompleteUpdate) {
        val backupId = runCatching {
            UUID.fromString(update.backupId)
                .toKotlinUuid()
        }.getOrNull() ?: return
        val completedAt = if (update.hasCompletedAt()) {
            Instant.fromEpochSeconds(update.completedAt.seconds, update.completedAt.nanos.toLong())
                .toLocalDateTime(TimeZone.UTC)
        }
        else {
            Clock.System.now()
                .toLocalDateTime(TimeZone.UTC)
        }

        transaction {
            Backups.update({ Backups.id eq backupId }) {
                if (update.success) {
                    it[Backups.status] = "COMPLETED"
                    it[Backups.filePath] = update.filePath.takeIf { s -> s.isNotEmpty() }
                    it[Backups.sizeBytes] = update.sizeBytes.takeIf { n -> n > 0 }
                }
                else {
                    it[Backups.status] = "FAILED"
                    it[Backups.errorMessage] = update.errorMessage.takeIf { s -> s.isNotEmpty() }
                }
                it[Backups.completedAt] = completedAt
            }
        }
    }

    // ── Alert evaluation ──────────────────────────────────────────────────────

    private suspend fun evaluateNodeAlerts(nodeId: String, metrics: NodeMetricsUpdate) {
        val kotlinNodeId = runCatching {
            UUID.fromString(nodeId)
                .toKotlinUuid()
        }.getOrNull() ?: return
        val now = Clock.System.now()
            .toLocalDateTime(TimeZone.UTC)

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
                    val msg = "Node $nodeId: $metric at ${"%.1f".format(currentValue)}%"
                    val eventId = AlertEvents.insert {
                        it[AlertEvents.thresholdId] = thresholdId
                        it[AlertEvents.firedAt] = now
                        it[AlertEvents.message] = msg
                    }[AlertEvents.id]
                    result += AlertEventNotification(eventId.toString(), thresholdId.toString(), ScopeType.NODE.name, nodeId, metric, msg, now.toUtcString())
                }
                else if (!triggered && openEvent != null) {
                    val eventId = openEvent[AlertEvents.id]
                    AlertEvents.update({ AlertEvents.id eq eventId }) { it[AlertEvents.resolvedAt] = now }
                    val msg = "Node $nodeId: $metric normalised"
                    result += AlertEventNotification(
                        eventId.toString(),
                        thresholdId.toString(),
                        ScopeType.NODE.name,
                        nodeId,
                        metric,
                        msg,
                        openEvent[AlertEvents.firedAt].toUtcString(),
                        now.toUtcString()
                    )
                }
            }
            result
        }

        notifications.forEach { _alertEventFlow.emit(it) }
    }

    private suspend fun evaluateServerAlerts(metrics: ContainerMetricsUpdate) {
        val kotlinServerId = runCatching {
            UUID.fromString(metrics.serverId)
                .toKotlinUuid()
        }.getOrNull() ?: return
        val now = Clock.System.now()
            .toLocalDateTime(TimeZone.UTC)

        val serverMemMb = transaction {
            Servers.selectAll()
                .where { Servers.id eq kotlinServerId }
                .firstOrNull()
                ?.get(Servers.memoryMb)
        } ?: return

        val metricValues = buildMap {
            put("cpu_percent", metrics.cpuPercent)
            if (serverMemMb > 0)
                put("ram_percent", metrics.ramUsedMb.toDouble() / serverMemMb * 100.0)
        }

        val notifications = transaction {
            val result = mutableListOf<AlertEventNotification>()
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
                    val msg = "Server ${metrics.serverId}: $metric at ${"%.1f".format(currentValue)}%"
                    val eventId = AlertEvents.insert {
                        it[AlertEvents.thresholdId] = thresholdId
                        it[AlertEvents.firedAt] = now
                        it[AlertEvents.message] = msg
                    }[AlertEvents.id]
                    result += AlertEventNotification(eventId.toString(), thresholdId.toString(), ScopeType.SERVER.name, metrics.serverId, metric, msg, now.toUtcString())
                }
                else if (!triggered && openEvent != null) {
                    val eventId = openEvent[AlertEvents.id]
                    AlertEvents.update({ AlertEvents.id eq eventId }) { it[AlertEvents.resolvedAt] = now }
                    val msg = "Server ${metrics.serverId}: $metric normalised"
                    result += AlertEventNotification(
                        eventId.toString(), thresholdId.toString(), ScopeType.SERVER.name,
                        metrics.serverId, metric, msg,
                        openEvent[AlertEvents.firedAt].toUtcString(), now.toUtcString()
                    )
                }
            }
            result
        }

        notifications.forEach { _alertEventFlow.emit(it) }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    fun generateNodeKey(): String {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes)
    }

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
