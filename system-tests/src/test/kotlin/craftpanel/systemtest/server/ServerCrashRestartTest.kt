package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.ServerStatus
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.core.annotation.Tags
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * Agent-owned crash recovery: an unexpected container death is restarted by the agent (within
 * the restart budget) without any master command.
 */
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
                val deadline = System.currentTimeMillis() + 60_000
                var running = false
                while (System.currentTimeMillis() < deadline && !running) {
                    delay(500.milliseconds)
                    running = runCatching {
                        docker.inspectContainerCmd(containerName(serverId)).exec().state?.running == true
                    }.getOrDefault(false)
                }
                running shouldBe true
                helper.awaitStatus(serverId, ServerStatus.HEALTHY, timeoutMs = 60_000)
            }
        }
    }
}
