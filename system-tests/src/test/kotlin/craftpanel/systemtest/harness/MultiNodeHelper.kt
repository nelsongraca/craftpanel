package craftpanel.systemtest.harness

import craftpanel.systemtest.client.api.DefaultApi
import craftpanel.systemtest.client.model.NodeResponse

class MultiNodeHelper(private val api: DefaultApi) {

    suspend fun trustAllPendingNodes(count: Int, timeoutMs: Long = 60_000): List<String> {
        val nodeIds = mutableListOf<String>()
        for (i in 0 until count) {
            val node = awaitPendingNode(timeoutMs)
            api.trustNode(node.id)

            pollUntilNotNull(timeoutMs) {
                api.getNode(node.id)
                    .takeIf { it.status == "ACTIVE" }
            } ?: error("Node ${node.id} did not transition to ACTIVE within ${timeoutMs}ms")

            nodeIds.add(node.id)
        }
        return nodeIds
    }

    private suspend fun awaitPendingNode(timeoutMs: Long): NodeResponse {
        var lastNodes: List<NodeResponse>? = null
        return pollUntilNotNull(timeoutMs) {
            api.listNodes()
                .also { lastNodes = it }
                .firstOrNull { it.status == "PENDING" }
        } ?: error(
            "No PENDING node appeared within ${timeoutMs}ms. " +
                    "Nodes: ${lastNodes?.map { "${it.id}=${it.status}" } ?: "none"}"
        )
    }
}