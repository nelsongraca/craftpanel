package craftpanel.systemtest.harness

import com.github.dockerjava.api.DockerClient
import kotlinx.coroutines.delay
import kotlin.random.Random

class NodeCleanupHelper(private val docker: DockerClient) {

    fun isContainerRunning(containerId: String): Boolean {
        return runCatching {
            val inspect = docker.inspectContainerCmd(containerId).exec()
            inspect.state?.running == true
        }.getOrDefault(false)
    }

    fun getContainerExitCode(containerId: String): Long? {
        return runCatching {
            val inspect = docker.inspectContainerCmd(containerId).exec()
            inspect.state?.exitCodeLong
        }.getOrNull()
    }

    suspend fun waitForContainerStop(containerId: String, timeoutMs: Long = 15_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var interval = 200L
        while (System.currentTimeMillis() < deadline) {
            if (!isContainerRunning(containerId)) return true
            val jitter = Random.nextLong(-(interval / 5), interval / 5 + 1)
            delay((interval + jitter).coerceAtLeast(50))
            interval = (interval * 1.5).toLong()
                .coerceAtMost(1000)
        }
        return false
    }

    fun restartContainer(containerId: String) {
        docker.restartContainerCmd(containerId).exec()
    }
}