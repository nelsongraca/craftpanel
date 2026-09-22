package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.PatchStopCommandRequest
import craftpanel.systemtest.client.model.ServerStatus
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.core.annotation.Isolate
import io.kotest.core.annotation.Tags
import io.kotest.matchers.shouldBe

/**
 * Signal stop commands (`^C`, `SIG*`) are delivered to the container's main process via
 * `docker kill --signal` instead of text on stdin. The fake-server is a JVM as PID 1, so a
 * delivered signal runs its shutdown hook and exits with `128 + signal` (130 for SIGINT,
 * 143 for SIGTERM). A text stop command exits 0 via `System.exit(0)`; a force kill exits 137.
 *
 * Each start waits for the fake-server's `TCP ping server listening` readiness marker before
 * stopping. The agent reports HEALTHY as soon as the container starts, but PID 1 drops signals
 * delivered before the JVM installs its handler (and before the shutdown hook is registered), so
 * an immediate stop would be lost and fall back to the 45s force-stop (exit 143, no hook log).
 */
@Isolate
@Tags("ServerCore")
class ServerSignalStopTest : BaseSystemTest() {

    init {
        lateinit var serverId: String

        beforeSpec {
            serverId = helper.createTestServer(nodeId)
        }
        afterSpec {
            runCatching {
                api.stopServer(serverId)
                helper.awaitStatus(serverId, ServerStatus.STOPPED)
                api.deleteServer(serverId)
            }
            helper.awaitStoppedOrGone(serverId)
        }

        context("Signal stop commands") {

            should("deliver SIGINT for a graceful exit with the ^C stop command") {
                api.updateStopCommand(serverId, PatchStopCommandRequest(stopCommand = "^C"))
                api.startServer(serverId)
                helper.awaitStatus(serverId, ServerStatus.HEALTHY)
                helper.awaitContainerLog(containerName(serverId), "[fake-server] TCP ping server listening", docker)

                api.stopServer(serverId)
                helper.awaitStoppedOrGone(serverId)

                api.getServer(serverId).status shouldBe ServerStatus.STOPPED
                helper.awaitContainerLog(containerName(serverId), "[fake-server] shutdown complete", docker)
                docker.inspectContainerCmd(containerName(serverId))
                    .exec().state?.exitCodeLong shouldBe 130L
            }

            should("deliver SIGTERM for a graceful exit with the SIGTERM stop command name") {
                api.updateStopCommand(serverId, PatchStopCommandRequest(stopCommand = "SIGTERM"))
                api.startServer(serverId)
                helper.awaitStatus(serverId, ServerStatus.HEALTHY)
                helper.awaitContainerLog(containerName(serverId), "[fake-server] TCP ping server listening", docker)

                api.stopServer(serverId)
                helper.awaitStoppedOrGone(serverId)

                api.getServer(serverId).status shouldBe ServerStatus.STOPPED
                helper.awaitContainerLog(containerName(serverId), "[fake-server] shutdown complete", docker)
                docker.inspectContainerCmd(containerName(serverId))
                    .exec().state?.exitCodeLong shouldBe 143L
            }

            should("write a text stop command to stdin instead of delivering a signal") {
                api.updateStopCommand(serverId, PatchStopCommandRequest(stopCommand = "stop"))
                api.startServer(serverId)
                helper.awaitStatus(serverId, ServerStatus.HEALTHY)
                helper.awaitContainerLog(containerName(serverId), "[fake-server] TCP ping server listening", docker)

                api.stopServer(serverId)
                helper.awaitStoppedOrGone(serverId)

                api.getServer(serverId).status shouldBe ServerStatus.STOPPED
                helper.awaitContainerLog(containerName(serverId), "[fake-server] stdin received: stop", docker)
                docker.inspectContainerCmd(containerName(serverId))
                    .exec().state?.exitCodeLong shouldBe 0L
            }
        }
    }
}
