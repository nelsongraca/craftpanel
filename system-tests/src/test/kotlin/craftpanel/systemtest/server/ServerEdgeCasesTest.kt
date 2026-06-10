package craftpanel.systemtest.server

import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.ServerHelper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException

class ServerEdgeCasesTest : BaseSystemTest() {

    init {
        describe("Server lifecycle edge cases") {

            describe("delete guards") {
                val helper = ServerHelper(api)
                lateinit var serverId: String

                beforeEach {
                    serverId = helper.createTestServer(nodeId)
                }
                afterEach {
                    runCatching { api.stopServer(serverId) }
                    helper.awaitStoppedOrGone(serverId)
                    runCatching { api.deleteServer(serverId) }
                }

                it("deleting a HEALTHY server returns 409") {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")
                    val ex = shouldThrow<ClientException> { api.deleteServer(serverId) }
                    ex.statusCode shouldBe 409
                }

                it("deleting a STARTING server returns 409") {
                    api.startServer(serverId)
                    val ex = shouldThrow<ClientException> { api.deleteServer(serverId) }
                    ex.statusCode shouldBe 409
                }

                it("stop then delete succeeds") {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")
                    api.stopServer(serverId)
                    helper.awaitStoppedOrGone(serverId)
                    api.deleteServer(serverId)
                }
            }

            describe("restart") {
                val helper = ServerHelper(api)
                lateinit var serverId: String

                beforeEach {
                    serverId = helper.createTestServer(nodeId)
                }
                afterEach {
                    runCatching { api.stopServer(serverId) }
                    helper.awaitStoppedOrGone(serverId)
                    runCatching { api.deleteServer(serverId) }
                }

                it("restarting a HEALTHY server transitions through STOPPING to HEALTHY") {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")

                    api.restartServer(serverId)

                    val afterRestart = helper.awaitStatus(serverId, "HEALTHY", timeoutMs = 120_000)
                    afterRestart.status shouldBe "HEALTHY"
                }

                it("restarting a STOPPED server returns 409") {
                    val ex = shouldThrow<ClientException> { api.restartServer(serverId) }
                    ex.statusCode shouldBe 409
                }
            }
        }
    }
}
