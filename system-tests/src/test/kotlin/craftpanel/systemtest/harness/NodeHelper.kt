package craftpanel.systemtest.harness

import craftpanel.systemtest.client.api.DefaultApi
import craftpanel.systemtest.client.model.NodeResponse
import kotlinx.coroutines.delay
import kotlin.random.Random
import org.openapitools.client.infrastructure.ServerException
import org.openapitools.client.infrastructure.ClientException

class NodeHelper(private val api: DefaultApi) {

    suspend fun trustFirstPendingNode(timeoutMs: Long = 30_000): String {
        var lastNodes: List<NodeResponse>? = null
        val node = pollUntilNotNull(timeoutMs) {
            api.listNodes()
                .also { lastNodes = it }
                .firstOrNull { it.status == "PENDING" }
        } ?: error(
            "No PENDING node appeared within ${timeoutMs}ms. " +
                    "Nodes: ${lastNodes?.map { "${it.id}=${it.status}" } ?: "none"}"
        )

        api.trustNode(node.id)

        pollUntilNotNull(timeoutMs) {
            api.getNode(node.id)
                .takeIf { it.status == "ACTIVE" }
        } ?: error("Node ${node.id} did not transition to ACTIVE within ${timeoutMs}ms")

        // Wait until agent actually connects to the master.
        // We do this by creating a temp server and trying to start it.
        // If the agent is not connected, the master returns 502 Bad Gateway.
        val serverHelper = ServerHelper(api)
        val tempServerId = serverHelper.createTestServer(node.id)
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
                error("Agent on node ${node.id} did not connect to master within ${timeoutMs}ms")
            }
            // Stop and delete the temp server
            runCatching { api.stopServer(tempServerId) }
            serverHelper.awaitStoppedOrGone(tempServerId)
        }
        finally {
            runCatching { api.deleteServer(tempServerId) }
        }

        return node.id
    }

    suspend fun awaitPendingNode(timeoutMs: Long = 30_000): NodeResponse {
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

    suspend fun pollUntilActive(
        id: String,
        timeoutMs: Long = 30_000,
    ): NodeResponse {
        var lastStatus: String? = null
        return pollUntilNotNull(timeoutMs) {
            api.getNode(id)
                .also { lastStatus = it.status }
                .takeIf { it.status == "ACTIVE" }
        } ?: error("Node $id did not transition to ACTIVE within ${timeoutMs}ms. Last status: ${lastStatus ?: "none"}")
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
