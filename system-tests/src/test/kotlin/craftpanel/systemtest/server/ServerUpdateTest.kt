package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.CreateNetworkRequest
import craftpanel.systemtest.client.model.UpdateServerRequest
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException

class ServerUpdateTest : BaseSystemTest() {

    init {

        lateinit var serverId: String

        beforeSpec {
            serverId = helper.createTestServer(nodeId)
        }
        afterSpec {
            runCatching { api.deleteServer(serverId) }
        }

        context("Server update") {

            should("updates server name") {
                api.updateServer(serverId, UpdateServerRequest(displayName = "renamed-server"))
                val server = api.getServer(serverId)
                server.displayName shouldBe "renamed-server"
            }

            should("updates server description") {
                api.updateServer(serverId, UpdateServerRequest(description = "test description"))
                val server = api.getServer(serverId)
                server.description shouldBe "test description"
            }

            should("updates server network") {
                val network = api.createNetwork(
                    CreateNetworkRequest(name = "update-net-${System.currentTimeMillis()}", type = "NORMAL")
                )
                try {
                    api.updateServer(serverId, UpdateServerRequest(networkId = network.id))
                    val server = api.getServer(serverId)
                    server.networkId shouldBe network.id
                }
                finally {
                    runCatching { api.deleteNetwork(network.id) }
                }
            }

            should("updating non-existent server returns 404") {
                shouldThrow<ClientException> {
                    api.updateServer(
                        "00000000-0000-0000-0000-000000000000",
                        UpdateServerRequest(displayName = "ghost")
                    )
                }.statusCode shouldBe 404
            }

            should("partial update only changes specified fields") {
                val original = api.getServer(serverId)
                val originalName = original.name

                api.updateServer(serverId, UpdateServerRequest(description = "only description"))
                val updated = api.getServer(serverId)
                updated.description shouldBe "only description"
                updated.name shouldBe originalName
            }

            should("update is idempotent") {
                api.updateServer(serverId, UpdateServerRequest(displayName = "idempotent-name"))
                api.updateServer(serverId, UpdateServerRequest(displayName = "idempotent-name"))
                val server = api.getServer(serverId)
                server.displayName shouldBe "idempotent-name"
            }
        }
    }
}