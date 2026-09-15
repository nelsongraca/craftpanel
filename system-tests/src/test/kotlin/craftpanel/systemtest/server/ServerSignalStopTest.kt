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
 */
@Isolate
@Tags("ServerCore")
class ServerSignalStopTest : BaseSystemTest() {

    init {
        context("Signal stop commands") {

            lateinit var serverId: String

            beforeContainer {
                serverId = helper.createTestServer(nodeId)
            }
            afterContainer {
                runCatching {
                    api.stopServer(serverId)
                    helper.awaitStatus(serverId, ServerStatus.STOPPED)
                    api.deleteServer(serverId)
                }
                helper.awaitStoppedOrGone(serverId)
            }

            should("^C stop command delivers SIGINT for a graceful exit") {
                api.updateStopCommand(serverId, PatchStopCommandRequest(stopCommand = "^C"))
                api.startServer(serverId)
                helper.awaitStatus(serverId, ServerStatus.HEALTHY)

                api.stopServer(serverId)
                helper.awaitStoppedOrGone(serverId)

                api.getServer(serverId).status shouldBe ServerStatus.STOPPED
                helper.awaitContainerLog(containerName(serverId), "[fake-server] shutdown complete", docker)
                docker.inspectContainerCmd(containerName(serverId))
                    .exec().state?.exitCodeLong shouldBe 130L
            }

            should("SIGTERM stop command name delivers SIGTERM for a graceful exit") {
                api.updateStopCommand(serverId, PatchStopCommandRequest(stopCommand = "SIGTERM"))
                api.startServer(serverId)
                helper.awaitStatus(serverId, ServerStatus.HEALTHY)

                api.stopServer(serverId)
                helper.awaitStoppedOrGone(serverId)

                api.getServer(serverId).status shouldBe ServerStatus.STOPPED
                helper.awaitContainerLog(containerName(serverId), "[fake-server] shutdown complete", docker)
                docker.inspectContainerCmd(containerName(serverId))
                    .exec().state?.exitCodeLong shouldBe 143L
            }

            should("text stop command is still written to stdin, not delivered as a signal") {
                api.updateStopCommand(serverId, PatchStopCommandRequest(stopCommand = "stop"))
                api.startServer(serverId)
                helper.awaitStatus(serverId, ServerStatus.HEALTHY)

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