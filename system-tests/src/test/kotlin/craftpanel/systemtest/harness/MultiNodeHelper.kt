package craftpanel.systemtest.harness

import craftpanel.systemtest.client.api.DefaultApi
import craftpanel.systemtest.client.model.NodeResponse
import craftpanel.systemtest.client.model.PatchNodeRequest
import craftpanel.systemtest.client.model.NodeStatus
import craftpanel.systemtest.client.model.NodeHealth

class MultiNodeHelper(private val api: DefaultApi) {

    suspend fun trustAllPendingNodes(count: Int, timeoutMs: Long = 60_000): List<String> {
        val nodeIds = mutableListOf<String>()
        repeat(count) {
            val node = awaitPendingNode(timeoutMs)
            api.trustNode(node.id)

            pollUntilNotNull(timeoutMs) {
                api.getNode(node.id)
                    .takeIf { it.status == NodeStatus.ACTIVE }
            } ?: error("Node ${node.id} did not transition to ACTIVE within ${timeoutMs}ms")

            // Assign non-overlapping port ranges so multi-node tests don't conflict
            val (portStart, portEnd) = PortBandAllocator.next()
            api.updateNode(node.id, PatchNodeRequest(portRangeStart = portStart, portRangeEnd = portEnd))

            pollUntilNotNull(timeoutMs) {
                api.getNode(node.id)
                    .takeIf { it.health == NodeHealth.HEALTHY }
            } ?: error("Agent on node ${node.id} did not become HEALTHY within ${timeoutMs}ms")

            nodeIds.add(node.id)
        }
        return nodeIds
    }

    private suspend fun awaitPendingNode(timeoutMs: Long): NodeResponse {
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
}