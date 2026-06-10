package craftpanel.systemtest.harness

import craftpanel.systemtest.client.api.DefaultApi
import craftpanel.systemtest.client.model.PatchNodeRequest
import craftpanel.systemtest.client.model.NodeResponse
import kotlinx.coroutines.delay
import kotlin.random.Random
import org.openapitools.client.infrastructure.ServerException
import org.openapitools.client.infrastructure.ClientException

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

            // Assign non-overlapping port ranges so multi-node tests don't conflict
            val portStart = 30000 + i * 1000
            val portEnd = portStart + 499
            api.updateNode(node.id, PatchNodeRequest(portRangeStart = portStart, portRangeEnd = portEnd))

            waitForAgentConnection(node.id, timeoutMs)

            nodeIds.add(node.id)
        }
        return nodeIds
    }

    private suspend fun waitForAgentConnection(nodeId: String, timeoutMs: Long) {
        val helper = ServerHelper(api)
        val tempServerId = helper.createTestServer(nodeId)
        try {
            var interval = 200L
            val deadline = System.currentTimeMillis() + timeoutMs
            var connected = false
            while (System.currentTimeMillis() < deadline && !connected) {
                try {
                    api.startServer(tempServerId)
                    connected = true
                }
                catch (e: Exception) {
                    if (isAgentNotConnectedError(e)) {
                        val jitter = Random.nextLong(-(interval / 5), interval / 5 + 1)
                        delay((interval + jitter).coerceAtLeast(50))
                        interval = (interval * 1.5).toLong()
                            .coerceAtMost(1000)
                    }
                    else {
                        throw e
                    }
                }
            }
            if (!connected) {
                error("Agent on node $nodeId did not connect to master within ${timeoutMs}ms")
            }
            // Let the server reach HEALTHY first
            helper.awaitStatus(tempServerId, "HEALTHY", timeoutMs)
            runCatching { api.stopServer(tempServerId) }
            helper.awaitStoppedOrGone(tempServerId)
            // Wait a moment after cleanup so agent fully releases resources
            delay(500)
        }
        finally {
            runCatching { api.deleteServer(tempServerId) }
        }
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

    private fun isAgentNotConnectedError(e: Throwable): Boolean {
        val statusCode = when (e) {
            is ServerException -> e.statusCode
            is ClientException -> e.statusCode
            else               -> return false
        }
        return statusCode == 502 && e.message?.contains("Agent not connected") == true
    }
}