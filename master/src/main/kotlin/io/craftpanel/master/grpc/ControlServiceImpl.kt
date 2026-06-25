package io.craftpanel.master.grpc

import io.craftpanel.master.auth.ScopeType
import io.craftpanel.proto.ContainerState
import io.craftpanel.proto.*
import io.craftpanel.master.config.NodeConfig
import io.craftpanel.master.database.schema.*
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
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import io.craftpanel.master.util.toUtcString
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.domain.NodeHealth
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.service.AgentGateway
import io.craftpanel.master.service.ServerRestartManager
import io.craftpanel.master.util.CryptoUtils
import kotlin.uuid.Uuid

class ControlServiceImpl(
    private val nodeConfig: NodeConfig,
    private val onNodeDisconnect: (String) -> Unit = {},
    // App-owned crash restart: bounded crash counter + a fire-and-forget restart action.
    // Both default to no-op so existing tests construct ControlServiceImpl unchanged.
    private val restartManager: ServerRestartManager? = null,
    private val restartServer: (Uuid) -> Unit = {},
) : ControlServiceGrpcKt.ControlServiceCoroutineImplBase(), AgentGateway {

    private val log = LoggerFactory.getLogger(ControlServiceImpl::class.java)
    private val random = SecureRandom()
    private val connectedAgents = ConcurrentHashMap<String, SendChannel<MasterMessage>>()

    // ── Data op correlation (keyed by "$nodeId/$requestId") ───────────────────
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<AgentMessage>>()
    private val consoleOutputChannels = ConcurrentHashMap<String, Channel<ConsoleOutput>>()

    // ── Observability flows ───────────────────────────────────────────────────
    private val _agentEvents = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 1024)

    override val agentEvents = _agentEvents.asSharedFlow()

    override fun sendToNode(nodeId: String, msg: MasterMessage): Boolean {
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
            "ACTIVE"  -> IdentifyNodeResponse.IdentifyStatus.ACTIVE
            "PENDING" -> IdentifyNodeResponse.IdentifyStatus.PENDING
            else      -> IdentifyNodeResponse.IdentifyStatus.REJECTED
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
        var lastEmittedHealth: NodeHealth? = null

        val watchdogJob = launch {
            while (!watchdogFired) {
                delay(60.seconds)
                val elapsed = Clock.System.now() - lastMetricsAt.get()
                if (elapsed.inWholeSeconds > 120 && !watchdogFired) {
                    watchdogFired = true
                    connectedNodeId?.let { nodeId ->
                        log.warn("Node $nodeId: no metrics for ${elapsed.inWholeSeconds}s — marking unreachable")
                        markNodeUnreachable(nodeId)
                        _agentEvents.emit(AgentEvent.NodeStatusEvent(nodeId, NodeHealth.UNREACHABLE))
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
                                Nodes.id eq Uuid.parse(nodeId)

                            }
                            .firstOrNull()
                            ?.get(Nodes.status)
                    }
                    log.info("Node $nodeId: first message, db status=$nodeStatus")
                    if (nodeStatus != "ACTIVE") {
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
                                    log.debug("Node {}: reconcileNodeState ok — emitting health={}", msg.nodeId, result.name)
                                    _agentEvents.emit(AgentEvent.NodeStatusEvent(msg.nodeId, result))
                                }
                                else {
                                    log.debug("Node ${msg.nodeId}: reconcileNodeState ok but node is PENDING — skipping health emit")
                                }
                            }
                            .onFailure { e -> log.error("Node ${msg.nodeId}: reconcileNodeState failed — ${e.message}", e) }
                        // Persist swarm_active from snapshot
                        runCatching {
                            transaction {
                                Nodes.update({ Nodes.id eq Uuid.parse(msg.nodeId) }) {
                                    it[Nodes.swarmActive] = msg.nodeState.swarmActive
                                }
                            }
                        }.onFailure { e -> log.warn("Node ${msg.nodeId}: failed to persist swarm_active — ${e.message}") }
                    }

                    msg.hasNodeMetrics()           -> {
                        lastMetricsAt.set(Clock.System.now())
                        val recordedAt = if (msg.nodeMetrics.hasRecordedAt())
                            Instant.fromEpochSeconds(msg.nodeMetrics.recordedAt.seconds, msg.nodeMetrics.recordedAt.nanos.toLong())
                        else Clock.System.now()
                        val nodeMetricEvent = AgentEvent.NodeMetricsEvent(
                            nodeId = msg.nodeId,
                            cpuPercent = msg.nodeMetrics.cpuPercent,
                            ramUsedMb = msg.nodeMetrics.ramUsedMb,
                            ramTotalMb = msg.nodeMetrics.ramTotalMb,
                            netInBytes = msg.nodeMetrics.netInBytes,
                            netOutBytes = msg.nodeMetrics.netOutBytes,
                            diskUsedBytes = msg.nodeMetrics.diskUsedBytes,
                            diskTotalBytes = msg.nodeMetrics.diskTotalBytes,
                            recordedAt = recordedAt,
                        )
                        runCatching { persistNodeMetrics(nodeMetricEvent) }
                            .onFailure { e -> log.warn("Node ${msg.nodeId}: persistNodeMetrics failed — ${e.message}") }
                        _agentEvents.emit(nodeMetricEvent)
                        // Router health derived from the periodic report; emit only on change
                        val newHealth = if (msg.nodeMetrics.routerRunning) NodeHealth.HEALTHY else NodeHealth.DEGRADED
                        if (newHealth != lastEmittedHealth) {
                            lastEmittedHealth = newHealth
                            runCatching { updateNodeHealth(msg.nodeId, newHealth) }
                                .onFailure { e -> log.warn("Node ${msg.nodeId}: updateNodeHealth failed — ${e.message}") }
                            _agentEvents.emit(AgentEvent.NodeStatusEvent(msg.nodeId, newHealth))
                        }
                        launch {
                            runCatching { evaluateNodeAlerts(msg.nodeId, msg.nodeMetrics) }
                                .onFailure { e -> if (e is CancellationException) throw e; log.warn("Node ${msg.nodeId}: evaluateNodeAlerts failed — ${e.message}") }
                        }
                    }

                    msg.hasContainerMetrics()      -> {
                        val recordedAt = if (msg.containerMetrics.hasRecordedAt())
                            Instant.fromEpochSeconds(msg.containerMetrics.recordedAt.seconds, msg.containerMetrics.recordedAt.nanos.toLong())
                        else Clock.System.now()
                        val containerMetricEvent = AgentEvent.ContainerMetricsEvent(
                            serverId = msg.containerMetrics.serverId,
                            cpuPercent = msg.containerMetrics.cpuPercent,
                            ramUsedMb = msg.containerMetrics.ramUsedMb,
                            netInBytes = msg.containerMetrics.netInBytes,
                            netOutBytes = msg.containerMetrics.netOutBytes,
                            blockInBytes = msg.containerMetrics.blockInBytes,
                            blockOutBytes = msg.containerMetrics.blockOutBytes,
                            recordedAt = recordedAt,
                        )
                        runCatching { persistContainerMetrics(containerMetricEvent) }
                            .onFailure { e -> log.warn("Node ${msg.nodeId}: persistContainerMetrics failed — ${e.message}") }
                        _agentEvents.emit(containerMetricEvent)
                        launch {
                            runCatching { evaluateServerAlerts(msg.containerMetrics) }
                                .onFailure { e -> if (e is CancellationException) throw e; log.warn("Node ${msg.nodeId}: evaluateServerAlerts failed — ${e.message}") }
                        }
                    }

                    msg.hasServerStatus()          -> {
                        val domainStatus = ServerStatus.fromProto(msg.serverStatus.status)
                        val serverStatusEvent = AgentEvent.ServerStatusEvent(
                            serverId = msg.serverStatus.serverId,
                            status = domainStatus,
                        )
                        runCatching { persistServerStatus(serverStatusEvent) }
                            .onFailure { e -> log.warn("Node ${msg.nodeId}: persistServerStatus failed — ${e.message}") }
                        _agentEvents.emit(serverStatusEvent)
                    }

                    msg.hasPlayerUpdate()          -> {
                        val recordedAt = if (msg.playerUpdate.hasRecordedAt())
                            Instant.fromEpochSeconds(msg.playerUpdate.recordedAt.seconds, msg.playerUpdate.recordedAt.nanos.toLong())
                        else Clock.System.now()
                        val playerUpdateEvent = AgentEvent.PlayerUpdateEvent(
                            serverId = msg.playerUpdate.serverId,
                            playerCount = msg.playerUpdate.playerCount,
                            playerNames = msg.playerUpdate.playerNamesList,
                            recordedAt = recordedAt,
                        )
                        runCatching { persistPlayerUpdate(msg.playerUpdate) }
                            .onFailure { e -> log.error("Node ${msg.nodeId}: persistPlayerUpdate failed — ${e.message}", e) }
                        _agentEvents.emit(playerUpdateEvent)
                    }

                    msg.hasBackupProgress()        -> _agentEvents.emit(
                        AgentEvent.BackupProgressEvent(
                            serverId = msg.backupProgress.serverId,
                            backupId = msg.backupProgress.backupId,
                            percentComplete = msg.backupProgress.percentComplete,
                        )
                    )

                    msg.hasBackupComplete()        -> {
                        val completedAt = if (msg.backupComplete.hasCompletedAt())
                            Instant.fromEpochSeconds(msg.backupComplete.completedAt.seconds, msg.backupComplete.completedAt.nanos.toLong())
                        else Clock.System.now()
                        val backupCompleteEvent = AgentEvent.BackupCompleteEvent(
                            serverId = msg.backupComplete.serverId,
                            backupId = msg.backupComplete.backupId,
                            success = msg.backupComplete.success,
                            sizeBytes = msg.backupComplete.sizeBytes,
                            errorMessage = if (!msg.backupComplete.success) msg.backupComplete.errorMessage else "",
                            completedAt = completedAt,
                        )
                        runCatching { persistBackupComplete(msg.backupComplete) }
                            .onFailure { e -> log.error("Node ${msg.nodeId}: persistBackupComplete failed — ${e.message}", e) }
                        _agentEvents.emit(backupCompleteEvent)
                    }

                    msg.hasRsyncReady()            -> _agentEvents.emit(
                        AgentEvent.RsyncReadyEvent(
                            migrationId = msg.rsyncReady.migrationId,
                            rsyncPassword = msg.rsyncReady.rsyncPassword,
                        )
                    )

                    msg.hasRsyncProgress()         -> _agentEvents.emit(
                        AgentEvent.RsyncProgressEvent(
                            migrationId = msg.rsyncProgress.migrationId,
                            isFinalPass = msg.rsyncProgress.isFinalPass,
                            percentComplete = msg.rsyncProgress.percentComplete,
                            bytesTransferred = msg.rsyncProgress.bytesTransferred,
                            phase = msg.rsyncProgress.phase,
                        )
                    )

                    msg.hasRsyncComplete()         -> _agentEvents.emit(
                        AgentEvent.RsyncCompleteEvent(
                            migrationId = msg.rsyncComplete.migrationId,
                            isFinalPass = msg.rsyncComplete.isFinalPass,
                            success = msg.rsyncComplete.success,
                            errorMessage = msg.rsyncComplete.errorMessage,
                        )
                    )

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
            watchdogJob.cancel()
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
                    log.warn("Node $nodeId: control stream disconnected — marking unreachable")
                    markNodeUnreachable(nodeId)
                    _agentEvents.emit(AgentEvent.NodeStatusEvent(nodeId, NodeHealth.UNREACHABLE))
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
            val reqId = Uuid.random()
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
            val inputJob = launch {
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
                inputJob.cancel()
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

    internal fun reconcileNodeState(nodeId: String, snapshot: NodeStateSnapshot): NodeHealth? {
        val kotlinNodeId = runCatching {
            Uuid.parse(nodeId)

        }.getOrNull() ?: return null
        val now = Clock.System.now()
            .toLocalDateTime(TimeZone.UTC)
        var resultHealth: NodeHealth? = null

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
                    val dbStatus = ServerStatus.fromDb(server[Servers.status])
                    val container = byServerId[serverId.toString()]

                    val newStatus: ServerStatus? = if (container == null) {
                        mapMissingContainer(dbStatus)
                    }
                    else {
                        mapContainerState(container.runState, dbStatus)
                    }

                    if (newStatus != null) {
                        log.info("Node $nodeId reconcile: server $serverId $dbStatus → $newStatus")
                        Servers.update({ Servers.id eq serverId }) {
                            it[Servers.status] = newStatus.toDb()
                            it[Servers.lastSeenAt] = now
                        }
                    }
                }

            if (currentStatus == "ACTIVE") {
                val newHealth = if (snapshot.routerRunning) NodeHealth.HEALTHY else NodeHealth.DEGRADED
                Nodes.update({ Nodes.id eq kotlinNodeId }) {
                    it[Nodes.health] = newHealth.name
                    it[Nodes.lastSeenAt] = now
                }
                resultHealth = newHealth
                log.debug("Node {}: reconciled health={} (routerRunning={})", nodeId, newHealth, snapshot.routerRunning)
            }
            else {
                log.debug("Node $nodeId: status=$currentStatus — only updating lastSeenAt")
                Nodes.update({ Nodes.id eq kotlinNodeId }) { it[Nodes.lastSeenAt] = now }
            }
        }
        return resultHealth
    }

    internal fun markNodeUnreachable(nodeId: String) {
        val kotlinNodeId = runCatching {
            Uuid.parse(nodeId)

        }.getOrElse {
            log.warn("markNodeUnreachable: invalid nodeId format: $nodeId")
            return
        }
        val now = Clock.System.now()
            .toLocalDateTime(TimeZone.UTC)

        transaction {
            val updated = Nodes.update({
                (Nodes.id eq kotlinNodeId) and (Nodes.status eq "ACTIVE")
            }) { it[Nodes.health] = "UNREACHABLE" }

            if (updated == 0) {
                log.debug("markNodeUnreachable: node $nodeId is not ACTIVE — skipping")
                return@transaction
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

            log.warn("Node $nodeId marked UNREACHABLE: $migrationCount migrations → FAILED, $backupCount backups → FAILED")
        }
    }

    internal fun updateNodeHealth(nodeId: String, health: NodeHealth) {
        val kotlinNodeId = runCatching {
            Uuid.parse(nodeId)

        }.getOrElse {
            log.warn("updateNodeHealth: invalid nodeId format: $nodeId")
            return
        }
        transaction {
            Nodes.update({ (Nodes.id eq kotlinNodeId) and (Nodes.status eq "ACTIVE") }) {
                it[Nodes.health] = health.name
            }
        }
    }

    // ── Metrics persistence ───────────────────────────────────────────────────

    private fun persistNodeMetrics(event: AgentEvent.NodeMetricsEvent) {
        val kotlinNodeId = runCatching {
            Uuid.parse(event.nodeId)
        }.getOrNull() ?: return
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
        val kotlinServerId = runCatching {
            Uuid.parse(event.serverId)
        }.getOrNull() ?: return
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
        val serverId = runCatching {
            Uuid.parse(event.serverId)
        }.getOrNull() ?: return
        val now = Clock.System.now()
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
        // Only the transition INTO unhealthy counts as a fresh crash — repeated UNHEALTHY
        // heartbeats must not each trigger another restart.
        val crashed = newStatus == ServerStatus.UNHEALTHY &&
                (prevStatus == ServerStatus.HEALTHY || prevStatus == ServerStatus.STARTING)
        if (!crashed) return
        if (mgr.recordCrashAndShouldRestart(serverId)) {
            runCatching { restartServer(serverId) }
                .onFailure { e -> log.error("Crash restart for server {} failed to dispatch — {}", serverId, e.message) }
        }
    }

    private fun persistPlayerUpdate(update: PlayerUpdate) {
        val serverId = runCatching {
            Uuid.parse(update.serverId)

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
            Uuid.parse(update.backupId)

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
            Uuid.parse(nodeId)

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
                    val msg = "Node $nodeId: $metric at ${"%.1f".format(currentValue)}%"
                    val eventId = AlertEvents.insert {
                        it[AlertEvents.thresholdId] = thresholdId
                        it[AlertEvents.firedAt] = now
                        it[AlertEvents.message] = msg
                    }[AlertEvents.id]
                    result += AgentEvent.AlertFiredEvent(eventId.toString(), thresholdId.toString(), ScopeType.NODE.name, nodeId, metric, msg, now.toUtcString(), null)
                }
                else if (!triggered && openEvent != null) {
                    val eventId = openEvent[AlertEvents.id]
                    AlertEvents.update({ AlertEvents.id eq eventId }) { it[AlertEvents.resolvedAt] = now }
                    val msg = "Node $nodeId: $metric normalised"
                    result += AgentEvent.AlertFiredEvent(
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

        notifications.forEach { _agentEvents.emit(it) }
    }

    private suspend fun evaluateServerAlerts(metrics: ContainerMetricsUpdate) {
        val kotlinServerId = runCatching {
            Uuid.parse(metrics.serverId)
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
                    val msg = "Server ${metrics.serverId}: $metric at ${"%.1f".format(currentValue)}%"
                    val eventId = AlertEvents.insert {
                        it[AlertEvents.thresholdId] = thresholdId
                        it[AlertEvents.firedAt] = now
                        it[AlertEvents.message] = msg
                    }[AlertEvents.id]
                    result += AgentEvent.AlertFiredEvent(eventId.toString(), thresholdId.toString(), ScopeType.SERVER.name, metrics.serverId, metric, msg, now.toUtcString(), null)
                }
                else if (!triggered && openEvent != null) {
                    val eventId = openEvent[AlertEvents.id]
                    AlertEvents.update({ AlertEvents.id eq eventId }) { it[AlertEvents.resolvedAt] = now }
                    val msg = "Server ${metrics.serverId}: $metric normalised"
                    result += AgentEvent.AlertFiredEvent(
                        eventId.toString(), thresholdId.toString(), ScopeType.SERVER.name,
                        metrics.serverId, metric, msg,
                        openEvent[AlertEvents.firedAt].toUtcString(), now.toUtcString()
                    )
                }
            }
            result
        }

        notifications.forEach { _agentEvents.emit(it) }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    fun generateNodeKey(): String = CryptoUtils.generateToken(32)

    private fun sha256Hex(input: String): String =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(input.toByteArray())
            )
}

fun mapContainerState(runState: ContainerState.RunState, dbStatus: ServerStatus): ServerStatus? = when {
    runState == ContainerState.RunState.RUNNING && dbStatus != ServerStatus.HEALTHY  -> ServerStatus.HEALTHY
    runState == ContainerState.RunState.STOPPED && dbStatus.isRunning                -> ServerStatus.STOPPED
    runState == ContainerState.RunState.EXITED && dbStatus != ServerStatus.UNHEALTHY -> ServerStatus.UNHEALTHY
    else                                                                             -> null
}

fun mapMissingContainer(dbStatus: ServerStatus): ServerStatus? =
    if (dbStatus != ServerStatus.STOPPED) ServerStatus.STOPPED else null
