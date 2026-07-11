package io.craftpanel.master.grpc.handlers

import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.domain.NodeHealth
import io.craftpanel.master.service.NodeStateReconciler
import io.craftpanel.proto.AgentMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Instant

class NodeMetricsHandler(private val agentEvents: MutableSharedFlow<AgentEvent>, private val nodeStateReconciler: NodeStateReconciler) {

    private val log = LoggerFactory.getLogger(NodeMetricsHandler::class.java)

    suspend fun handle(msg: AgentMessage, nodeId: String, lastMetricsAt: AtomicReference<Instant>, lastEmittedHealth: AtomicReference<NodeHealth?>) {
        if (!msg.hasNodeMetrics()) {
            log.warn("NodeMetricsHandler called with non-nodeMetrics message: ${msg.payloadCase}")
            return
        }
        val nodeMetrics = msg.nodeMetrics
        lastMetricsAt.set(Clock.System.now())
        val recordedAt = if (nodeMetrics.hasRecordedAt()) {
            Instant.fromEpochSeconds(nodeMetrics.recordedAt.seconds, nodeMetrics.recordedAt.nanos.toLong())
        } else {
            Clock.System.now()
        }
        val nodeMetricEvent = AgentEvent.NodeMetricsEvent(
            nodeId = nodeId,
            cpuPercent = nodeMetrics.cpuPercent,
            ramUsedMb = nodeMetrics.ramUsedMb,
            ramTotalMb = nodeMetrics.ramTotalMb,
            netInBytes = nodeMetrics.netInBytes,
            netOutBytes = nodeMetrics.netOutBytes,
            diskUsedBytes = nodeMetrics.diskUsedBytes,
            diskTotalBytes = nodeMetrics.diskTotalBytes,
            recordedAt = recordedAt
        )
        agentEvents.emit(nodeMetricEvent)
        val newHealth = if (nodeMetrics.routerRunning) NodeHealth.HEALTHY else NodeHealth.DEGRADED
        if (newHealth != lastEmittedHealth.get()) {
            lastEmittedHealth.set(newHealth)
            runCatching { nodeStateReconciler.updateNodeHealth(nodeId, newHealth) }
                .onFailure { e -> log.warn("Node $nodeId: updateNodeHealth failed — ${e.message}") }
            agentEvents.emit(AgentEvent.NodeStatusEvent(nodeId, newHealth))
        }
    }
}
