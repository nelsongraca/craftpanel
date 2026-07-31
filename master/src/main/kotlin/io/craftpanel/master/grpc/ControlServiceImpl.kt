package io.craftpanel.master.grpc

import io.craftpanel.master.domain.*
import io.craftpanel.master.grpc.handlers.*
import io.craftpanel.master.service.AgentGateway
import io.craftpanel.master.service.NodeStateReconciler
import io.craftpanel.master.service.repo.BackupRepository
import io.craftpanel.master.service.repo.ServerRepository
import io.craftpanel.master.util.formatSymlinkTimestamp
import io.craftpanel.proto.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ControlServiceImpl(
    private val nodeStateReconciler: NodeStateReconciler,
    private val nodeRegistrar: NodeRegistrar,
    private val onNodeDisconnect: (String) -> Unit = {},
    // Shared agent events flow (passed to handlers)
    private val agentEventsFlow: MutableSharedFlow<AgentEvent>,
    // Shared data op context (drained on node disconnect)
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
    private val serverRepository: ServerRepository,
    private val backupRepository: BackupRepository
) : ControlServiceGrpcKt.ControlServiceCoroutineImplBase(),
    AgentGateway {

    private val log = LoggerFactory.getLogger(ControlServiceImpl::class.java)
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

    /**
     * Reconnect self-heal: after a node reconciles its state snapshot, push the full
     * symlink-overlay mapping (servers-by-name + backups-by-server) so the agent can
     * rebuild both trees from canonical storage. Deliberately conservative — only ever
     * adds symlinks; never prunes. See server-path-navigation plan, Task 4.
     */
    private fun sendRebuildSymlinks(nodeId: String) {
        val kotlinNodeId = runCatching { Uuid.parse(nodeId) }.getOrNull() ?: return
        runCatching { buildRebuildSymlinksCommand(kotlinNodeId) }
            .onSuccess { command -> sendToNode(nodeId, command) }
            .onFailure { e -> log.error("Node $nodeId: failed to send RebuildSymlinksCommand — ${e.message}", e) }
    }

    @OptIn(com.google.protobuf.kotlin.OnlyForUseByGeneratedProtoCode::class)
    internal fun buildRebuildSymlinksCommand(nodeId: Uuid): MasterMessage {
        val servers = serverRepository.listByNodeId(nodeId)
        val backups = servers.flatMap { server ->
            backupRepository.listBackups(server.id)
                .filter { it.status == "COMPLETED" && !it.filePath.isNullOrEmpty() }
                .map { server to it }
        }
        val builder = RebuildSymlinksCommand.newBuilder()
        servers.forEach { server ->
            builder.addServersBuilder()
                .setServerId(server.id.toString())
                .setServerName(server.name)
        }
        backups.forEach { (server, backup) ->
            builder.addBackupsBuilder()
                .setBackupId(backup.id.toString())
                .setServerId(server.id.toString())
                .setServerName(server.name)
                .setCreatedAtFormatted(formatSymlinkTimestamp(backup.createdAt))
                .setFilePath(backup.filePath ?: "")
        }
        return masterMessage { rebuildSymlinks = builder.build() }
    }

    // ── gRPC: registration / identification ──────────────────────────────────

    override suspend fun registerNode(request: RegisterNodeRequest): RegisterNodeResponse = nodeRegistrar.registerNode(request)

    override suspend fun identifyNode(request: IdentifyNodeRequest): IdentifyNodeResponse = nodeRegistrar.identifyNode(request)

    // ── gRPC: control stream ─────────────────────────────────────────────────

    override fun control(requests: Flow<AgentMessage>): Flow<MasterMessage> = channelFlow {
        log.info("control stream opened")
        val outChannel = this.channel
        val connectedNodeId = AtomicReference<String?>(null)
        val lastMetricsAt = AtomicReference(Clock.System.now())
        val lastEmittedHealth = AtomicReference<NodeHealth?>(null)
        val watchdogFired = AtomicBoolean(false)

        val watchdogJob = startWatchdog(connectedNodeId, lastMetricsAt, watchdogFired)
        try {
            requests.collect { msg ->
                log.info("control stream msg: nodeId=${msg.nodeId}, hasNodeState=${msg.hasNodeState()}, hasNodeMetrics=${msg.hasNodeMetrics()}")
                if (connectedNodeId.get() == null) {
                    authenticate(msg.nodeId, outChannel)
                    connectedNodeId.set(msg.nodeId)
                }
                dispatch(msg, lastMetricsAt, lastEmittedHealth)
            }
        } finally {
            watchdogJob.cancel()
            connectedNodeId.get()
                ?.let { teardown(it, outChannel, watchdogFired.get()) }
        }
    }

    private fun ProducerScope<MasterMessage>.startWatchdog(connectedNodeId: AtomicReference<String?>, lastMetricsAt: AtomicReference<Instant>, watchdogFired: AtomicBoolean): Job = launch {
        while (!watchdogFired.get()) {
            delay(60.seconds)
            val elapsed = Clock.System.now() - lastMetricsAt.get()
            if (elapsed.inWholeSeconds > 120 && watchdogFired.compareAndSet(false, true)) {
                connectedNodeId.get()
                    ?.let { nodeId ->
                        log.warn("Node $nodeId: no metrics for ${elapsed.inWholeSeconds}s — marking unreachable")
                        nodeStateReconciler.markNodeUnreachable(nodeId)
                        agentEventsFlow.emit(AgentEvent.NodeStatusEvent(nodeId, NodeHealth.UNREACHABLE))
                    }
            }
        }
    }

    private fun authenticate(nodeId: String, outChannel: SendChannel<MasterMessage>) {
        nodeRegistrar.requireActive(nodeId)
        connectedAgents[nodeId] = outChannel
        log.debug("Node $nodeId: registered in connectedAgents (channel=${System.identityHashCode(outChannel)})")
    }

    private suspend fun dispatch(msg: AgentMessage, lastMetricsAt: AtomicReference<Instant>, lastEmittedHealth: AtomicReference<NodeHealth?>) {
        when {
            msg.hasNodeState() -> {
                nodeStateHandler.handle(msg, msg.nodeId)
                sendRebuildSymlinks(msg.nodeId)
            }

            msg.hasNodeMetrics() -> nodeMetricsHandler.handle(msg, msg.nodeId, lastMetricsAt, lastEmittedHealth)

            msg.hasContainerMetrics() -> containerMetricsHandler.handle(msg, msg.nodeId)

            msg.hasServerStatus() -> serverStatusHandler.handle(msg, msg.nodeId)

            msg.hasPlayerUpdate() -> playerUpdateHandler.handle(msg, msg.nodeId)

            msg.hasBackupProgress() -> backupHandler.handleBackupProgress(msg, msg.nodeId)

            msg.hasBackupComplete() -> backupHandler.handleBackupComplete(msg, msg.nodeId)

            msg.hasRsyncReady() -> migrationHandler.handleRsyncReady(msg, msg.nodeId)

            msg.hasRsyncProgress() -> migrationHandler.handleRsyncProgress(msg, msg.nodeId)

            msg.hasRsyncComplete() -> migrationHandler.handleRsyncComplete(msg, msg.nodeId)

            msg.hasConsoleOutput() -> dataOpResponseHandler.handle(msg, msg.nodeId)

            msg.hasListFilesResponse() -> dataOpResponseHandler.handle(msg, msg.nodeId)

            msg.hasReadFileResponse() -> dataOpResponseHandler.handle(msg, msg.nodeId)

            msg.hasWriteFileResponse() -> dataOpResponseHandler.handle(msg, msg.nodeId)

            msg.hasDeleteFileResponse() -> dataOpResponseHandler.handle(msg, msg.nodeId)

            msg.hasMakeDirectoryResponse() -> dataOpResponseHandler.handle(msg, msg.nodeId)

            msg.hasMoveFileResponse() -> dataOpResponseHandler.handle(msg, msg.nodeId)

            msg.hasCopyFileResponse() -> dataOpResponseHandler.handle(msg, msg.nodeId)

            msg.hasDownloadFileResponse() -> dataOpResponseHandler.handle(msg, msg.nodeId)

            msg.hasUploadFileResponse() -> dataOpResponseHandler.handle(msg, msg.nodeId)

            msg.hasFetchContainerLogsResponse() -> dataOpResponseHandler.handle(msg, msg.nodeId)

            else -> log.debug("Node ${msg.nodeId} sent unhandled message type")
        }
    }

    private suspend fun teardown(nodeId: String, outChannel: SendChannel<MasterMessage>, watchdogFired: Boolean) {
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
        } else if (wasOwner && connectedAgents.containsKey(nodeId)) {
            log.info("Node $nodeId: stream ended but new connection is already active — skipping degrade")
        } else if (!wasOwner) {
            log.debug("Node $nodeId: stream finally skipped — not owner (superseded by newer connection)")
        }
    }

    // ── Data op routing helpers ───────────────────────────────────────────────

    private fun drainNodeRequests(nodeId: String) {
        val prefix = "$nodeId/"
        pendingRequests.entries.removeIf { (k, v) ->
            if (k.startsWith(prefix)) {
                v.completeExceptionally(Exception("Node $nodeId disconnected"))
                true
            } else {
                false
            }
        }
        consoleOutputChannels.entries.removeIf { (k, v) ->
            if (k.startsWith(prefix)) {
                v.close(Exception("Node $nodeId disconnected"))
                true
            } else {
                false
            }
        }
    }
}
