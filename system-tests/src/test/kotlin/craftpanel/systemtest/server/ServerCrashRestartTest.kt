package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.ServerStatus
import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.SharedStack
import io.kotest.core.annotation.Isolate
import io.kotest.core.annotation.Tags
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * Agent-owned crash recovery: an unexpected container death is restarted by the agent (within
 * the restart budget) without any master command — including after an agent process restart and
 * when the death happens while the agent is down.
 *
 * [Isolate] because the agent-restart cases deliberately bounce the shared agent container.
 */
@Isolate
@Tags("ServerOps")
class ServerCrashRestartTest : BaseSystemTest() {

    init {
        lateinit var serverId: String

        beforeSpec {
            serverId = helper.createTestServer(nodeId)
        }
        afterSpec {
            runCatching {
                api.stopServer(serverId)
                helper.awaitStoppedOrGone(serverId)
                api.deleteServer(serverId)
            }
        }

        /** Brings the shared server to HEALTHY if it is not already (tests share one server). */
        suspend fun ensureRunning() {
            val current = runCatching { api.getServer(serverId) }.getOrNull()
            if (current?.status != ServerStatus.HEALTHY) {
                api.startServer(serverId)
                helper.awaitStatus(serverId, ServerStatus.HEALTHY)
            }
        }

        /** Polls Docker until the server container reports running (or fails the given deadline). */
        suspend fun awaitContainerRunning(timeoutMs: Long = 90_000) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val running = runCatching {
                    docker.inspectContainerCmd(containerName(serverId)).exec().state?.running == true
                }.getOrDefault(false)
                if (running) return
                delay(500.milliseconds)
            }
            error("Server container for $serverId did not start within ${timeoutMs}ms")
        }

        context("Crash recovery") {

            should("auto-restart a crashed container within budget") {
                api.startServer(serverId)
                helper.awaitStatus(serverId, ServerStatus.HEALTHY)

                // Simulate an unexpected container death (SIGKILL). The agent's die watcher must
                // restart it because desired state is RUNNING and the budget allows it.
                docker.killContainerCmd(containerName(serverId)).exec()

                // Poll for the container to be running again. A stale HEALTHY report can satisfy
                // awaitStatus before the agent has processed the death, so wait on the container
                // itself first, then confirm the synthesized status.
                awaitContainerRunning()
                helper.awaitStatus(serverId, ServerStatus.HEALTHY, timeoutMs = 60_000)
            }

            should("still restart an unexpected death after the agent process restarts") {
                ensureRunning()

                // Bounce the agent: its in-memory ownership set is emptied and rebuilt from the
                // desired-state re-push on reconnect. A death after that must still be reported.
                docker.restartContainerCmd(SharedStack.agentContainerId).exec()
                nodeHelper.pollUntilActive(nodeId)
                delay(3_000.milliseconds)

                docker.killContainerCmd(containerName(serverId)).exec()

                awaitContainerRunning()
                helper.awaitStatus(serverId, ServerStatus.HEALTHY, timeoutMs = 60_000)
            }

            should("restart a server that died while the agent was down, on reconnect") {
                ensureRunning()

                // Take the agent down, kill the server while no watcher is listening (the death is
                // lost), then bring the agent back. Reconnect + desired-state re-push must recover it.
                docker.stopContainerCmd(SharedStack.agentContainerId)
                    .withTimeout(30)
                    .exec()
                // Ensure the agent process is stopped before killing the server.
                val stoppedDeadline = System.currentTimeMillis() + 30_000
                while (System.currentTimeMillis() < stoppedDeadline &&
                    runCatching { docker.inspectContainerCmd(SharedStack.agentContainerId).exec().state?.running == true }
                        .getOrDefault(false)
                ) {
                    delay(500.milliseconds)
                }
                docker.killContainerCmd(containerName(serverId)).exec()

                docker.startContainerCmd(SharedStack.agentContainerId).exec()
                nodeHelper.pollUntilActive(nodeId)

                awaitContainerRunning()
                helper.awaitStatus(serverId, ServerStatus.HEALTHY, timeoutMs = 60_000)
            }
        }
    }
}
