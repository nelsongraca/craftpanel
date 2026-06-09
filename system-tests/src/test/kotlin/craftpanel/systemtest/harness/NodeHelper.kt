package craftpanel.systemtest.harness

import craftpanel.systemtest.client.api.DefaultApi
import craftpanel.systemtest.client.model.NodeResponse
import kotlinx.coroutines.delay

import org.openapitools.client.infrastructure.ServerException
import org.openapitools.client.infrastructure.ClientException

class NodeHelper(private val api: DefaultApi) {

    suspend fun trustFirstPendingNode(timeoutMs: Long = 30_000): String {
        val node = pollUntilNotNull(timeoutMs) {
            api.listNodes()
                .firstOrNull { it.status == "PENDING" }
        } ?: error("No PENDING node appeared within ${timeoutMs}ms")

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
            val deadline = System.currentTimeMillis() + timeoutMs
            var connected = false
            while (System.currentTimeMillis() < deadline && !connected) {
                try {
                    api.startServer(tempServerId)
                    connected = true
                } catch (e: ServerException) {
                    if (e.statusCode == 502 && e.message?.contains("Agent not connected") == true) {
                        delay(250)
                    } else {
                        throw e
                    }
                } catch (e: ClientException) {
                    if (e.statusCode == 502 && e.message?.contains("Agent not connected") == true) {
                        delay(250)
                    } else {
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
        } finally {
            runCatching { api.deleteServer(tempServerId) }
        }

        return node.id
    }

    suspend fun awaitPendingNode(timeoutMs: Long = 30_000): NodeResponse =
        pollUntilNotNull(timeoutMs) {
            api.listNodes()
                .firstOrNull { it.status == "PENDING" }
        } ?: error("No PENDING node appeared within ${timeoutMs}ms")

    suspend fun pollUntilActive(
        id: String,
        timeoutMs: Long = 30_000,
    ): NodeResponse =
        pollUntilNotNull(timeoutMs) {
            api.getNode(id)
                .takeIf { it.status == "ACTIVE" }
        } ?: error("Node $id did not transition to ACTIVE within ${timeoutMs}ms")
}

private suspend fun <T> pollUntilNotNull(timeoutMs: Long, block: suspend () -> T?): T? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        block()?.let { return it }
        delay(500)
    }
    return null
}
