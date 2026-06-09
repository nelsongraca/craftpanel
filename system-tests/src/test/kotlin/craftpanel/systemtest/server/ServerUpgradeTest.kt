package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.UpgradeServerRequest
import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.ServerHelper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException

class ServerUpgradeTest : BaseSystemTest() {

    init {
        describe("Server upgrade") {

            describe("upgrade while stopped") {
                val helper = ServerHelper(api)
                lateinit var serverId: String

                beforeEach {
                    serverId = helper.createTestServer(nodeId)
                }
                afterEach {
                    runCatching { api.deleteServer(serverId) }
                }

                it("upgrades server version tag when stopped") {
                    api.upgradeServer(serverId, UpgradeServerRequest(itzgImageTag = "1.21.5"))
                    val server = api.getServer(serverId)
                    server.status shouldBe "STOPPED"
                    server.itzgImageTag shouldBe "1.21.5"
                }
            }

            describe("errors") {
                it("returns 404 for non-existent server") {
                    val ex = shouldThrow<ClientException> {
                        api.upgradeServer(
                            "00000000-0000-0000-0000-000000000000",
                            UpgradeServerRequest(itzgImageTag = "latest")
                        )
                    }
                    ex.statusCode shouldBe 404
                }
            }
        }
    }
}
