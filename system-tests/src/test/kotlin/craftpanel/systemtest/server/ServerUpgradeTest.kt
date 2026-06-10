package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.UpgradeServerRequest
import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.ServerHelper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.openapitools.client.infrastructure.ClientException

class ServerUpgradeTest : BaseSystemTest() {

    init {
        describe("Server upgrade") {

            describe("upgrade when stopped") {
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

                it("upgrade while running returns 409") {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")
                    val ex = shouldThrow<ClientException> {
                        api.upgradeServer(serverId, UpgradeServerRequest(itzgImageTag = "1.21.5"))
                    }
                    ex.statusCode shouldBe 409
                }

                it("blank itzg_image_tag returns 422") {
                    val ex = shouldThrow<ClientException> {
                        api.upgradeServer(serverId, UpgradeServerRequest(itzgImageTag = ""))
                    }
                    ex.statusCode shouldBe 422
                }

                it("invalid version tag returns 400") {
                    val ex = shouldThrow<ClientException> {
                        api.upgradeServer(serverId, UpgradeServerRequest(itzgImageTag = "99.99.99"))
                    }
                    ex.statusCode shouldBe 400
                }

                it("stop, upgrade, start with new version") {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")
                    api.stopServer(serverId)
                    helper.awaitStoppedOrGone(serverId)
                    api.upgradeServer(serverId, UpgradeServerRequest(itzgImageTag = "1.21.5"))
                    var server = api.getServer(serverId)
                    server.status shouldBe "STOPPED"
                    server.itzgImageTag shouldBe "1.21.5"
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")
                    server = api.getServer(serverId)
                    server.itzgImageTag shouldBe "1.21.5"
                }

                it("container env var reflects version after upgrade and restart") {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")
                    api.stopServer(serverId)
                    helper.awaitStoppedOrGone(serverId)
                    api.upgradeServer(serverId, UpgradeServerRequest(itzgImageTag = "1.21.5"))
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")
                    val info = docker.inspectContainerCmd(containerName(serverId)).exec()
                    val env = info.config?.env?.associate {
                        val parts = it.split("=", limit = 2)
                        parts[0] to parts.getOrElse(1) { "" }
                    }.orEmpty()
                    env["VERSION"] shouldBe "1.21.5"
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
