package craftpanel.systemtest.harness

import craftpanel.systemtest.client.api.DefaultApi
import craftpanel.systemtest.client.model.*

class NodeHelper(private val api: DefaultApi) {

    suspend fun trustFirstPendingNode(timeoutMs: Long = 30_000): String {
        var lastNodes: List<NodeResponse>? = null
        val node = pollUntilNotNull(timeoutMs) {
            api.listNodes()
                .also { lastNodes = it }
                .firstOrNull { it.status == NodeStatus.PENDING }
        } ?: error(
            "No PENDING node appeared within ${timeoutMs}ms. " +
                "Nodes: ${lastNodes?.map { "${it.id}=${it.status}" } ?: "none"}"
        )

        api.trustNode(node.id)

        pollUntilNotNull(timeoutMs) {
            api.getNode(node.id)
                .takeIf { it.status == NodeStatus.ACTIVE }
        } ?: error("Node ${node.id} did not transition to ACTIVE within ${timeoutMs}ms")

        // Assign a unique, non-overlapping port band to avoid host-port collisions
        // between stacks when containers from a previous run are still shutting down.
        val (portStart, portEnd) = PortBandAllocator.next()
        api.updateNode(node.id, PatchNodeRequest(portRangeStart = portStart, portRangeEnd = portEnd))

        pollUntilNotNull(timeoutMs) {
            api.getNode(node.id)
                .takeIf { it.health == NodeHealth.HEALTHY }
        } ?: error("Agent on node ${node.id} did not become HEALTHY within ${timeoutMs}ms")

        return node.id
    }

    suspend fun awaitPendingNode(timeoutMs: Long = 30_000): NodeResponse {
        var lastNodes: List<NodeResponse>? = null
        return pollUntilNotNull(timeoutMs) {
            api.listNodes()
                .also { lastNodes = it }
                .firstOrNull { it.status == NodeStatus.PENDING }
        } ?: error(
            "No PENDING node appeared within ${timeoutMs}ms. " +
                "Nodes: ${lastNodes?.map { "${it.id}=${it.status}" } ?: "none"}"
        )
    }

    suspend fun pollUntilActive(id: String, timeoutMs: Long = 30_000): NodeResponse {
        var lastStatus: NodeStatus? = null
        return pollUntilNotNull(timeoutMs) {
            api.getNode(id)
                .also { lastStatus = it.status }
                .takeIf { it.status == NodeStatus.ACTIVE }
        } ?: error("Node $id did not transition to ACTIVE within ${timeoutMs}ms. Last status: ${lastStatus ?: "none"}")
    }

    /**
     * Wait until a node is ACTIVE *and* HEALTHY. ACTIVE is a DB status set on IdentifyNode, which
     * happens before the agent's control stream is registered — so a caller that only checks ACTIVE
     * can race the agent's reconnect and hit "agent not connected". HEALTHY is driven by the first
     * metrics message on the live stream, making it a reliable liveness signal.
     */
    suspend fun pollUntilHealthy(id: String, timeoutMs: Long = 60_000): NodeResponse {
        var last: NodeResponse? = null
        return pollUntilNotNull(timeoutMs) {
            api.getNode(id)
                .also { last = it }
                .takeIf { it.status == NodeStatus.ACTIVE && it.health == NodeHealth.HEALTHY }
        } ?: error(
            "Node $id did not become ACTIVE+HEALTHY within ${timeoutMs}ms. " +
                "Last: status=${last?.status ?: "none"}, health=${last?.health ?: "none"}"
        )
    }
}
