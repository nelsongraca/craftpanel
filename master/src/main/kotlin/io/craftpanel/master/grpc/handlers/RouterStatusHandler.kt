package io.craftpanel.master.grpc.handlers

import io.craftpanel.master.service.RouterStatus
import io.craftpanel.master.service.RouterStatusStore
import io.craftpanel.proto.AgentMessage
import io.craftpanel.proto.NodeStateSnapshot
import org.slf4j.LoggerFactory

/**
 * Records the mc-router liveness/update state an agent reports, either as the opening node-state
 * snapshot or as a live [AgentMessage.routerStatus] change. Backs the node-page "router update
 * available" signal so an operator can plan the downtime before applying a pre-pulled image.
 */
class RouterStatusHandler(private val store: RouterStatusStore) {

    private val log = LoggerFactory.getLogger(RouterStatusHandler::class.java)

    fun handle(msg: AgentMessage, nodeId: String) {
        if (!msg.hasRouterStatus()) {
            log.warn("RouterStatusHandler called with non-routerStatus message: ${msg.payloadCase}")
            return
        }
        val status = msg.routerStatus
        store.update(nodeId, RouterStatus(running = status.running, updateAvailable = status.updateAvailable))
    }

    fun handleSnapshot(snapshot: NodeStateSnapshot, nodeId: String) {
        store.update(nodeId, RouterStatus(running = snapshot.routerRunning, updateAvailable = snapshot.routerUpdateAvailable))
    }

    fun clear(nodeId: String) {
        store.clear(nodeId)
    }
}
