package io.craftpanel.master.grpc

import io.craftpanel.proto.*
import io.craftpanel.master.config.NodeConfig
import io.craftpanel.master.database.schema.*
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.domain.NodeHealth
import io.craftpanel.master.service.AgentGateway
import io.craftpanel.master.service.NodeStateReconciler
import io.craftpanel.master.util.CryptoUtils
import io.craftpanel.master.grpc.handlers.*
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ControlServiceImpl(
    private val nodeConfig: NodeConfig,
    private val nodeStateReconciler: NodeStateReconciler,
    private val onNodeDisconnect: (String) -> Unit = {},
    // Shared agent events flow (passed to handlers)
    private val agentEventsFlow: MutableSharedFlow<AgentEvent>,
    // Shared data op context (passed to DataOpResponseHandler)
    private val dataOpContext: DataOpContext,
    // Handlers (all share the same agentEventsFlow)
    private val nodeStateHandler: NodeStateHandler,
    private val nodeMetricsHandler: NodeMetricsHandler,
    private val containerMetricsHandler: ContainerMetricsHandler,
    private val serverStatusHandler: ServerStatusHandler,
    private val playerUpdateHandler: PlayerUpdateHandler,
    private val backupHandler: BackupHandler,
    private val migrationHandler: MigrationHandler,
    private val dataOpResponseHandler: DataOpResponseHandler,
) : ControlServiceGrpcKt.ControlServiceCoroutineImplBase(), AgentGateway {

    private val log = LoggerFactory.getLogger(ControlServiceImpl::class.java)
    private val random = SecureRandom()
    private val connectedAgents = ConcurrentHashMap<String, SendChannel<MasterMessage>>()

    // ── Observability flows ───────────────────────────────────────────────────
    override val agentEvents = agentEventsFlow.asSharedFlow()

    // ── Data op correlation (delegated to DataOpContext) ─────────────────
    private val pendingRequests get() = dataOpContext.pendingRequests
    private val consoleOutputChannels get() = dataOpContext.consoleOutputChannels

    /** Exposed so NodeObserver can emit events (alert firings) back through the bus. */
    suspend fun emitToAgentEvents(event: AgentEvent) {
        agentEventsFlow.emit(event)
    }

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
        val lastEmittedHealth = AtomicReference<NodeHealth?>(null)
        var watchdogFired = false

        val watchdogJob = launch {
            while (!watchdogFired) {
                delay(60.seconds)
                val elapsed = Clock.System.now() - lastMetricsAt.get()
                if (elapsed.inWholeSeconds > 120 && !watchdogFired) {
                    watchdogFired = true
                    connectedNodeId?.let { nodeId ->
                        log.warn("Node $nodeId: no metrics for ${elapsed.inWholeSeconds}s — marking unreachable")
                        nodeStateReconciler.markNodeUnreachable(nodeId)
                        agentEventsFlow.emit(AgentEvent.NodeStatusEvent(nodeId, NodeHealth.UNREACHABLE))
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
                            .where { Nodes.id eq Uuid.parse(nodeId) }
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
                    msg.hasNodeState()             -> nodeStateHandler.handle(msg, msg.nodeId)
                    msg.hasNodeMetrics()           -> nodeMetricsHandler.handle(msg, msg.nodeId, lastMetricsAt, lastEmittedHealth)
                    msg.hasContainerMetrics()      -> containerMetricsHandler.handle(msg, msg.nodeId)
                    msg.hasServerStatus()          -> serverStatusHandler.handle(msg, msg.nodeId)
                    msg.hasPlayerUpdate()          -> playerUpdateHandler.handle(msg, msg.nodeId)
                    msg.hasBackupProgress()        -> backupHandler.handleBackupProgress(msg, msg.nodeId)
                    msg.hasBackupComplete()        -> backupHandler.handleBackupComplete(msg, msg.nodeId)
                    msg.hasRsyncReady()            -> migrationHandler.handleRsyncReady(msg, msg.nodeId)
                    msg.hasRsyncProgress()         -> migrationHandler.handleRsyncProgress(msg, msg.nodeId)
                    msg.hasRsyncComplete()         -> migrationHandler.handleRsyncComplete(msg, msg.nodeId)
                    msg.hasConsoleOutput()         -> dataOpResponseHandler.handle(msg, msg.nodeId)
                    msg.hasListFilesResponse()     -> dataOpResponseHandler.handle(msg, msg.nodeId)
                    msg.hasReadFileResponse()      -> dataOpResponseHandler.handle(msg, msg.nodeId)
                    msg.hasWriteFileResponse()     -> dataOpResponseHandler.handle(msg, msg.nodeId)
                    msg.hasDeleteFileResponse()    -> dataOpResponseHandler.handle(msg, msg.nodeId)
                    msg.hasMakeDirectoryResponse() -> dataOpResponseHandler.handle(msg, msg.nodeId)
                    msg.hasMoveFileResponse()      -> dataOpResponseHandler.handle(msg, msg.nodeId)
                    msg.hasCopyFileResponse()      -> dataOpResponseHandler.handle(msg, msg.nodeId)
                    msg.hasDownloadFileResponse()  -> dataOpResponseHandler.handle(msg, msg.nodeId)
                    msg.hasUploadFileResponse()    -> dataOpResponseHandler.handle(msg, msg.nodeId)
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
                        connectedAgents.containsKey(nodeId)
                    }"
                )
                drainNodeRequests(nodeId)
                onNodeDisconnect(nodeId)
                if (wasOwner && !watchdogFired && !connectedAgents.containsKey(nodeId)) {
                    log.warn("Node $nodeId: control stream disconnected — marking unreachable")
                    nodeStateReconciler.markNodeUnreachable(nodeId)
                    agentEventsFlow.emit(AgentEvent.NodeStatusEvent(nodeId, NodeHealth.UNREACHABLE))
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

    // ── Utility ───────────────────────────────────────────────────────────────

    fun generateNodeKey(): String = CryptoUtils.generateToken(32)

    private fun sha256Hex(input: String): String =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(input.toByteArray())
            )
}
