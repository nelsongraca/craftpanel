package io.craftpanel.master.grpc

import io.craftpanel.master.domain.*
import io.craftpanel.master.grpc.handlers.*
import io.craftpanel.master.service.AgentRuntimeSettingsService
import io.craftpanel.master.service.NodeMetadata as NodeMetadataDto
import io.craftpanel.master.service.NodeNotActiveException
import io.craftpanel.master.service.NodeRegistrationService
import io.craftpanel.master.service.NodeStateReconciler
import io.craftpanel.proto.*
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ControlServiceImpl(
    private val nodeStateReconciler: NodeStateReconciler,
    private val nodeRegistrationService: NodeRegistrationService,
    private val onNodeDisconnect: (String) -> Unit = {},
    private val registry: AgentRegistry,
    // Shared data op context (drained on node disconnect)
    private val dataOpContext: DataOpContext,
    // Handlers (all share the same agent-events bus)
    private val nodeStateHandler: NodeStateHandler,
    private val nodeMetricsHandler: NodeMetricsHandler,
    private val containerMetricsHandler: ContainerMetricsHandler,
    private val serverStatusHandler: ServerStatusHandler,
    private val playerUpdateHandler: PlayerUpdateHandler,
    private val backupHandler: BackupHandler,
    private val migrationHandler: MigrationHandler,
    private val dataOpResponseHandler: DataOpResponseHandler,
    // Install-wide agent runtime tuning included in register/identify responses.
    private val agentRuntimeSettingsService: AgentRuntimeSettingsService
) : ControlServiceGrpcKt.ControlServiceCoroutineImplBase() {

    private val log = LoggerFactory.getLogger(ControlServiceImpl::class.java)

    // ── gRPC: registration / identification ──────────────────────────────────

    override suspend fun registerNode(request: RegisterNodeRequest): RegisterNodeResponse {
        val registered = nodeRegistrationService.register(request.bootstrapToken, request.metadata.toDto())
        return registerNodeResponse {
            nodeKey = registered.rawKey
            nodeId = registered.nodeId.toString()
            runtimeSettings = agentRuntimeSettingsService.snapshot()
        }
    }

    override suspend fun identifyNode(request: IdentifyNodeRequest): IdentifyNodeResponse {
        val identified = nodeRegistrationService.identify(request.nodeKey, request.metadata.toDto())
        return identifyNodeResponse {
            status = when (identified.status) {
                NodeStatus.ACTIVE  -> IdentifyNodeResponse.IdentifyStatus.ACTIVE
                NodeStatus.PENDING -> IdentifyNodeResponse.IdentifyStatus.PENDING
                else               -> IdentifyNodeResponse.IdentifyStatus.REJECTED
            }
            nodeId = identified.nodeId?.toString() ?: ""
            runtimeSettings = agentRuntimeSettingsService.snapshot()
        }
    }

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
                    // Auth failure (e.g. PENDING/REJECTED node) MUST propagate — it closes the stream.
                    authenticate(msg.nodeId, outChannel)
                    connectedNodeId.set(msg.nodeId)
                }
                // A handler throw must never tear down the bidirectional stream: an uncaught
                // exception propagates through channelFlow and deregisters the agent, forcing a
                // reconnect (and a window where sendToNode fails). Re-throw cancellation only.
                runCatching { dispatch(msg, lastMetricsAt, lastEmittedHealth) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        log.error("Node ${msg.nodeId}: control-stream message handling failed (${msg.payloadCase})", e)
                    }
            }
        }
        finally {
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
                        registry.emit(AgentEvent.NodeStatusEvent(nodeId, NodeHealth.UNREACHABLE))
                    }
            }
        }
    }

    private fun authenticate(nodeId: String, outChannel: SendChannel<MasterMessage>) {
        try {
            nodeRegistrationService.requireActive(Uuid.parse(nodeId))
        }
        catch (e: NodeNotActiveException) {
            throw StatusException(Status.PERMISSION_DENIED.withDescription(e.message))
        }
        registry.register(nodeId, outChannel)
    }

    private suspend fun dispatch(msg: AgentMessage, lastMetricsAt: AtomicReference<Instant>, lastEmittedHealth: AtomicReference<NodeHealth?>) {
        when {
            msg.hasNodeState()        -> {
                nodeStateHandler.handle(msg, msg.nodeId)
                registry.rebuildSymlinks(msg.nodeId)
            }

            msg.hasNodeMetrics()      -> nodeMetricsHandler.handle(msg, msg.nodeId, lastMetricsAt, lastEmittedHealth)

            msg.hasContainerMetrics() -> containerMetricsHandler.handle(msg)

            msg.hasServerStatus()     -> serverStatusHandler.handle(msg)

            msg.hasPlayerUpdate()     -> playerUpdateHandler.handle(msg)

            msg.hasBackupProgress()   -> backupHandler.handleBackupProgress(msg)

            msg.hasBackupComplete()   -> backupHandler.handleBackupComplete(msg)

            msg.hasRsyncReady()       -> migrationHandler.handleRsyncReady(msg)

            msg.hasRsyncProgress()    -> migrationHandler.handleRsyncProgress(msg)

            msg.hasRsyncComplete()    -> migrationHandler.handleRsyncComplete(msg)

            // Console output + every unary file/console response type: DataOpResponseHandler owns
            // the correlation switch, so dispatch forwards the whole family in one branch.
            else                      -> dataOpResponseHandler.handle(msg, msg.nodeId)
        }
    }

    private suspend fun teardown(nodeId: String, outChannel: SendChannel<MasterMessage>, watchdogFired: Boolean) {
        val wasOwner = registry.deregister(nodeId, outChannel)
        log.debug(
            "Node $nodeId: stream finally — wasOwner=$wasOwner, watchdogFired=$watchdogFired, channel=${System.identityHashCode(outChannel)}, stillConnected=${
                registry.isConnected(nodeId)
            }"
        )
        drainNodeRequests(nodeId)
        onNodeDisconnect(nodeId)
        if (wasOwner && !watchdogFired && !registry.isConnected(nodeId)) {
            log.warn("Node $nodeId: control stream disconnected — marking unreachable")
            nodeStateReconciler.markNodeUnreachable(nodeId)
            registry.emit(AgentEvent.NodeStatusEvent(nodeId, NodeHealth.UNREACHABLE))
        }
        else if (wasOwner && registry.isConnected(nodeId)) {
            log.info("Node $nodeId: stream ended but new connection is already active — skipping degrade")
        }
        else if (!wasOwner) {
            log.debug("Node $nodeId: stream finally skipped — not owner (superseded by newer connection)")
        }
    }

    // ── Data op routing helpers ───────────────────────────────────────────────

    private fun drainNodeRequests(nodeId: String) {
        val prefix = "$nodeId/"
        dataOpContext.pendingRequests.entries.removeIf { (k, v) ->
            if (k.startsWith(prefix)) {
                v.completeExceptionally(Exception("Node $nodeId disconnected"))
                true
            }
            else {
                false
            }
        }
        dataOpContext.consoleOutputChannels.entries.removeIf { (k, v) ->
            if (k.startsWith(prefix)) {
                v.close(Exception("Node $nodeId disconnected"))
                true
            }
            else {
                false
            }
        }
    }
}

private fun NodeMetadata.toDto() = NodeMetadataDto(
    hostname = hostname,
    publicIp = publicIp,
    privateIp = privateIp,
    totalRamMb = totalRamMb,
    reservedRamMb = reservedRamMb,
    totalCpuMillicores = totalCpuMillicores,
    reservedCpuMillicores = reservedCpuMillicores,
    agentVersion = agentVersion
)
