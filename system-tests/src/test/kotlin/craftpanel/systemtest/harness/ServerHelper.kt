package craftpanel.systemtest.harness

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.Frame
import craftpanel.systemtest.client.api.DefaultApi
import craftpanel.systemtest.client.model.CreateServerRequest
import craftpanel.systemtest.client.model.ServerResponse
import kotlinx.coroutines.delay

class ServerHelper(private val api: DefaultApi) {

    suspend fun createTestServer(nodeId: String): String {
        val response = api.createServer(
            CreateServerRequest(
                name = "test-${System.currentTimeMillis()}",
                nodeId = nodeId,
                serverType = "PAPER",
                mcVersion = "1.21.4",
                itzgImageTag = "latest",
                memoryMb = 512,
                cpuShares = 128,
            )
        )
        return response.id
    }

    suspend fun awaitStatus(id: String, status: String, timeoutMs: Long = 60_000): ServerResponse =
        pollUntilNotNull(timeoutMs) {
            api.getServer(id)
                .takeIf { it.status == status }
        } ?: error("Server $id did not reach status $status within ${timeoutMs}ms")

    suspend fun awaitContainerLog(containerName: String, substring: String, docker: DockerClient, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val logs = StringBuilder()
            runCatching {
                docker.logContainerCmd(containerName)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(false)
                    .exec(object : ResultCallback.Adapter<Frame>() {
                        override fun onNext(frame: Frame) {
                            logs.append(String(frame.payload))
                        }
                    })
                    .awaitCompletion()
            }
            if (substring in logs) return
            delay(500)
        }
    }

    suspend fun awaitPlayerCount(id: String, expected: Int, timeoutMs: Long = 90_000): ServerResponse =
        pollUntilNotNull(timeoutMs) {
            api.getServer(id)
                .takeIf { it.lastPlayerCount == expected }
        } ?: error("Server $id did not reach lastPlayerCount=$expected within ${timeoutMs}ms")

    suspend fun awaitStoppedOrGone(id: String, timeoutMs: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val result = runCatching { api.getServer(id) }
            when {
                result.isFailure                        -> return
                result.getOrNull()?.status == "STOPPED" -> return
            }
            delay(500)
        }
    }
}

private suspend fun <T> pollUntilNotNull(timeoutMs: Long, block: suspend () -> T?): T? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        block()?.let { return it }
        delay(500)
    }
    return null
}
