package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.ServerStatus
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Isolate
import io.kotest.core.annotation.Tags
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException

@Isolate
@Tags("ServerCore")
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
                    helper.awaitStoppedOrGone(serverId, timeoutMs = 60_000)
                    runCatching { api.deleteServer(serverId) }
                }

                should("return 409 when deleting a HEALTHY server") {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, ServerStatus.HEALTHY, timeoutMs = 180_000)
                    val ex = shouldThrow<ClientException> { api.deleteServer(serverId) }
                    ex.statusCode shouldBe 409
                }

                should("return 409 when deleting a STARTING server") {
                    api.startServer(serverId)
                    val ex = shouldThrow<ClientException> { api.deleteServer(serverId) }
                    ex.statusCode shouldBe 409
                }

                should("stop a server and then delete it successfully") {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, ServerStatus.HEALTHY, timeoutMs = 180_000)
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
                    helper.awaitStoppedOrGone(serverId, timeoutMs = 60_000)
                    runCatching { api.deleteServer(serverId) }
                }

                should("restart a HEALTHY server through STOPPING back to HEALTHY") {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, ServerStatus.HEALTHY)

                    api.restartServer(serverId)

                    val afterRestart = helper.awaitStatus(serverId, ServerStatus.HEALTHY, timeoutMs = 120_000)
                    afterRestart.status shouldBe ServerStatus.HEALTHY
                }

                should("return 409 when restarting a STOPPED server") {
                    val ex = shouldThrow<ClientException> { api.restartServer(serverId) }
                    ex.statusCode shouldBe 409
                }
            }
        }
    }
}
