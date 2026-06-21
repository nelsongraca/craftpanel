package craftpanel.systemtest.harness

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.Frame
import craftpanel.systemtest.client.api.DefaultApi
import craftpanel.systemtest.client.model.CreateServerRequest
import craftpanel.systemtest.client.model.ServerResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.openapitools.client.infrastructure.ClientException
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class ServerHelper(private val api: DefaultApi) {

    private val httpClient = OkHttpClient()

    suspend fun uploadFile(serverId: String, path: String, content: ByteArray, token: String) {
        withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("path", path)
                .addFormDataPart("file", "file", content.toRequestBody("application/octet-stream".toMediaType()))
                .build()
            val request = Request.Builder()
                .url("${SharedStack.masterApiUrl}/api/servers/$serverId/files/upload")
                .addHeader("Authorization", "Bearer $token")
                .post(body)
                .build()
            val response = httpClient.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) error("Upload failed: ${it.code} ${it.body?.string()}")
            }
        }
    }

    suspend fun createTestServer(nodeId: String, memoryMb: Int = 512, cpuShares: Int = 0): String {
        val response = api.createServer(
            CreateServerRequest(
                name = "test-${System.currentTimeMillis()}",
                nodeId = nodeId,
                serverType = "PAPER",
                mcVersion = "1.21.4",
                itzgImageTag = "latest",
                memoryMb = memoryMb,
                cpuShares = cpuShares,
            )
        )
        return response.id
    }

    suspend fun awaitStatus(id: String, status: String, timeoutMs: Long = 120_000): ServerResponse {
        var lastResponse: ServerResponse? = null
        val result = pollUntilNotNull(timeoutMs) {
            api.getServer(id)
                .also { lastResponse = it }
                .takeIf { it.status == status }
        }
        return result ?: error(
            "Server $id did not reach status $status within ${timeoutMs}ms. " +
                    "Last status: ${lastResponse?.status ?: "none"}"
        )
    }

    suspend fun awaitContainerLog(containerName: String, substring: String, docker: DockerClient, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var interval = 100L
        while (System.currentTimeMillis() < deadline) {
            val logs = StringBuilder()
            runCatching {
                docker.logContainerCmd(containerName)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTail(100)
                    .exec(object : ResultCallback.Adapter<Frame>() {
                        override fun onNext(frame: Frame) {
                            logs.append(String(frame.payload))
                        }
                    })
                    .awaitCompletion()
            }
            if (substring in logs) return
            val jitter = Random.nextLong(-(interval / 5), interval / 5 + 1)
            delay((interval + jitter).coerceAtLeast(50).milliseconds)
            interval = (interval * 1.5).toLong()
                .coerceAtMost(1000)
        }
    }

    suspend fun awaitPlayerCount(id: String, expected: Int, timeoutMs: Long = 90_000): ServerResponse {
        var lastResponse: ServerResponse? = null
        val result = pollUntilNotNull(timeoutMs) {
            api.getServer(id)
                .also { lastResponse = it }
                .takeIf { it.lastPlayerCount == expected }
        }
        return result ?: error(
            "Server $id did not reach lastPlayerCount=$expected within ${timeoutMs}ms. " +
                    "Last count: ${lastResponse?.lastPlayerCount ?: "none"}"
        )
    }


    suspend fun awaitBackupCompleted(serverId: String, backupId: String, timeoutMs: Long = 60_000) {
        pollUntilNotNull(timeoutMs) {
            api.listBackups(serverId)
                .getOrDefault("backups", emptyList())
                .firstOrNull { it.id == backupId && it.status in setOf("COMPLETED", "FAILED") }
        } ?: error("Backup $backupId did not complete within ${timeoutMs}ms")
    }

    suspend fun awaitStoppedOrGone(id: String, timeoutMs: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var interval = 100L
        var lastStatus: String? = null
        while (System.currentTimeMillis() < deadline) {
            val result = runCatching { api.getServer(id) }
            when {
                result.exceptionOrNull() is ClientException
                        && (result.exceptionOrNull() as ClientException).statusCode == 404 -> return

                result.getOrNull()?.status.also { lastStatus = it } == "STOPPED"           -> return
            }
            val jitter = Random.nextLong(-(interval / 5), interval / 5 + 1)
            delay((interval + jitter).coerceAtLeast(50).milliseconds)
            interval = (interval * 1.5).toLong()
                .coerceAtMost(1000)
        }
        println("[cleanup] Server $id did not stop within ${timeoutMs}ms (last status: ${lastStatus ?: "none"})")
    }
}
