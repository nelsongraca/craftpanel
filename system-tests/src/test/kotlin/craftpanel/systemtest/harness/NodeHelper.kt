package craftpanel.systemtest.harness

import craftpanel.systemtest.client.api.DefaultApi
import craftpanel.systemtest.client.model.IocraftpanelmasterserviceNodeResponse
import kotlinx.coroutines.delay

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

        return node.id
    }

    suspend fun awaitPendingNode(timeoutMs: Long = 30_000): IocraftpanelmasterserviceNodeResponse =
        pollUntilNotNull(timeoutMs) {
            api.listNodes()
                .firstOrNull { it.status == "PENDING" }
        } ?: error("No PENDING node appeared within ${timeoutMs}ms")

    suspend fun pollUntilActive(
        id: String,
        timeoutMs: Long = 30_000,
    ): IocraftpanelmasterserviceNodeResponse =
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
