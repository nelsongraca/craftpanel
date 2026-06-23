package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.ServerStatus
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException

class ServerEdgeCasesTest : BaseSystemTest() {

    init {
        context("Server lifecycle edge cases") {

            context("delete guards") {

            lateinit var serverId: String

                beforeEach {
                    serverId = helper.createTestServer(nodeId)
                }
                afterEach {
                    runCatching { api.stopServer(serverId) }
                    helper.awaitStoppedOrGone(serverId)
                    runCatching { api.deleteServer(serverId) }
                }

                should("deleting a HEALTHY server returns 409") {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, ServerStatus.HEALTHY)
                    val ex = shouldThrow<ClientException> { api.deleteServer(serverId) }
                    ex.statusCode shouldBe 409
                }

                should("deleting a STARTING server returns 409") {
                    api.startServer(serverId)
                    val ex = shouldThrow<ClientException> { api.deleteServer(serverId) }
                    ex.statusCode shouldBe 409
                }

                should("stop then delete succeeds") {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, ServerStatus.HEALTHY)
                    api.stopServer(serverId)
                    helper.awaitStoppedOrGone(serverId)
                    api.deleteServer(serverId)
                }
            }

            context("restart") {

            lateinit var serverId: String

                beforeEach {
                    serverId = helper.createTestServer(nodeId)
                }
                afterEach {
                    runCatching { api.stopServer(serverId) }
                    helper.awaitStoppedOrGone(serverId)
                    runCatching { api.deleteServer(serverId) }
                }

                should("restarting a HEALTHY server transitions through STOPPING to HEALTHY") {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, ServerStatus.HEALTHY)

                    api.restartServer(serverId)

                    val afterRestart = helper.awaitStatus(serverId, ServerStatus.HEALTHY, timeoutMs = 120_000)
                    afterRestart.status shouldBe "HEALTHY"
                }

                should("restarting a STOPPED server returns 409") {
                    val ex = shouldThrow<ClientException> { api.restartServer(serverId) }
                    ex.statusCode shouldBe 409
                }
            }
        }
    }
}
