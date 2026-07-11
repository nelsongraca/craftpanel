package io.craftpanel.master.grpc.handlers

import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.service.NodeStateReconciler
import io.craftpanel.proto.AgentMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import org.slf4j.LoggerFactory

class NodeStateHandler(private val agentEvents: MutableSharedFlow<AgentEvent>, private val nodeStateReconciler: NodeStateReconciler) {

    private val log = LoggerFactory.getLogger(NodeStateHandler::class.java)

    suspend fun handle(msg: AgentMessage, nodeId: String) {
        if (!msg.hasNodeState()) {
            log.warn("NodeStateHandler called with non-nodeState message: ${msg.payloadCase}")
            return
        }
        val nodeState = msg.nodeState
        log.info("Node $nodeId sent state snapshot with ${nodeState.containersCount} containers")
        runCatching { nodeStateReconciler.reconcileNodeState(nodeId, nodeState) }
            .onSuccess { result ->
                if (result != null) {
                    log.debug("Node $nodeId: reconcileNodeState ok — emitting health=${result.name}")
                    agentEvents.emit(AgentEvent.NodeStatusEvent(nodeId, result))
                } else {
                    log.debug("Node $nodeId: reconcileNodeState ok but node is PENDING — skipping health emit")
                }
            }
            .onFailure { e -> log.error("Node $nodeId: reconcileNodeState failed — ${e.message}", e) }
    }
}
