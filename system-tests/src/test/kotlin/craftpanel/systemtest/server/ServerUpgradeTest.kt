package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.ServerStatus
import craftpanel.systemtest.client.model.UpdateServerRequest
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.core.annotation.Tags
import io.kotest.matchers.shouldBe

@Tags("ServerOps")
class ServerUpgradeTest : BaseSystemTest() {

    init {
        context("Server reconfigure") {

            context("mc_version change") {

                lateinit var serverId: String

                beforeEach {
                    serverId = helper.createTestServer(nodeId)
                }
                afterEach {
                    runCatching { api.deleteServer(serverId) }
                }

                should("persist mc_version after PATCH") {
                    api.updateServer(serverId, UpdateServerRequest(mcVersion = "1.21.5"))
                    val server = api.getServer(serverId)
                    server.mcVersion shouldBe "1.21.5"
                }

                should("reflect mc_version in the container VERSION env after start") {
                    api.updateServer(serverId, UpdateServerRequest(mcVersion = "1.21.5"))
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, ServerStatus.HEALTHY)
                    val info = docker.inspectContainerCmd(containerName(serverId))
                        .exec()
                    val env = info.config?.env?.associate {
                        val parts = it.split("=", limit = 2)
                        parts[0] to parts.getOrElse(1) { "" }
                    }
                        .orEmpty()
                    env["VERSION"] shouldBe "1.21.5"
                }

                should("update the container VERSION env after stop, PATCH, restart") {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, ServerStatus.HEALTHY)
                    api.stopServer(serverId)
                    helper.awaitStoppedOrGone(serverId)
                    api.updateServer(serverId, UpdateServerRequest(mcVersion = "1.21.5"))
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, ServerStatus.HEALTHY)
                    val info = docker.inspectContainerCmd(containerName(serverId))
                        .exec()
                    val env = info.config?.env?.associate {
                        val parts = it.split("=", limit = 2)
                        parts[0] to parts.getOrElse(1) { "" }
                    }
                        .orEmpty()
                    env["VERSION"] shouldBe "1.21.5"
                }
            }

            context("itzg_image_tag change") {

                lateinit var serverId: String

                beforeEach {
                    serverId = helper.createTestServer(nodeId)
                }
                afterEach {
                    runCatching { api.deleteServer(serverId) }
                }

                should("persist itzg_image_tag after PATCH") {
                    api.updateServer(serverId, UpdateServerRequest(itzgImageTag = "latest"))
                    val server = api.getServer(serverId)
                    server.itzgImageTag shouldBe "latest"
                }
            }
        }
    }
}
