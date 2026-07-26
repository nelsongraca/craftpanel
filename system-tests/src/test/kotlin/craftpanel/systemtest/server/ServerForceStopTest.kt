package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.PatchStopCommandRequest
import craftpanel.systemtest.client.model.ServerStatus
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Isolate
import io.kotest.core.annotation.Tags
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException

@Isolate
@Tags("ServerCore")
class ServerForceStopTest : BaseSystemTest() {

    init {
        context("Direct force stop on HEALTHY server") {

            lateinit var serverId: String

            beforeEach {
                serverId = helper.createTestServer(nodeId)
            }
            afterEach {
                runCatching { helper.awaitStoppedOrGone(serverId, timeoutMs = 60_000) }
                runCatching { api.deleteServer(serverId) }
            }

            should("force stop a HEALTHY server transitions it to STOPPED") {
                api.startServer(serverId)
                helper.awaitStatus(serverId, ServerStatus.HEALTHY)

                api.forceStopServer(serverId)
                helper.awaitStatus(serverId, ServerStatus.STOPPED)

                val server = api.getServer(serverId)
                server.status shouldBe ServerStatus.STOPPED
            }

            should("force stopping an already STOPPED server returns 409") {
                api.startServer(serverId)
                helper.awaitStatus(serverId, ServerStatus.HEALTHY)
                api.forceStopServer(serverId)
                helper.awaitStatus(serverId, ServerStatus.STOPPED)

                val ex = shouldThrow<ClientException> { api.forceStopServer(serverId) }
                ex.statusCode shouldBe 409
            }
        }

        context("Force stop overrides stuck graceful stop") {

            lateinit var serverId: String

            beforeEach {
                serverId = helper.createTestServer(nodeId)
                api.updateStopCommand(serverId, PatchStopCommandRequest(stopCommand = "nope"))
            }
            afterEach {
                runCatching { helper.awaitStoppedOrGone(serverId, timeoutMs = 60_000) }
                runCatching { api.deleteServer(serverId) }
            }

            should("graceful stop hangs but force stop kills the container") {
                api.startServer(serverId)
                helper.awaitStatus(serverId, ServerStatus.HEALTHY)

                api.stopServer(serverId)

                val stopping = helper.awaitStatus(serverId, ServerStatus.STOPPING, timeoutMs = 10_000)
                stopping.status shouldBe ServerStatus.STOPPING

                api.forceStopServer(serverId)
                helper.awaitStatus(serverId, ServerStatus.STOPPED, timeoutMs = 30_000)

                val server = api.getServer(serverId)
                server.status shouldBe ServerStatus.STOPPED
            }
        }
    }
}
