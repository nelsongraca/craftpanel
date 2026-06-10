package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.CreateNetworkRequest
import craftpanel.systemtest.client.model.CreateServerRequest
import craftpanel.systemtest.client.model.UpdateServerRequest
import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.ServerHelper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.openapitools.client.infrastructure.ClientException

class ServerUpdateTest : BaseSystemTest() {

    init {
        context("Server update") {

            should("updates server name") {
                val helper = ServerHelper(api)
                val serverId = helper.createTestServer(nodeId)
                try {
                    api.updateServer(serverId, UpdateServerRequest(displayName = "renamed-server"))
                    val server = api.getServer(serverId)
                    server.displayName shouldBe "renamed-server"
                } finally {
                    runCatching { api.deleteServer(serverId) }
                }
            }

            should("updates server description") {
                val helper = ServerHelper(api)
                val serverId = helper.createTestServer(nodeId)
                try {
                    api.updateServer(serverId, UpdateServerRequest(description = "test description"))
                    val server = api.getServer(serverId)
                    server.description shouldBe "test description"
                } finally {
                    runCatching { api.deleteServer(serverId) }
                }
            }

            should("updates server network") {
                val helper = ServerHelper(api)
                val serverId = helper.createTestServer(nodeId)
                val network = api.createNetwork(
                    CreateNetworkRequest(name = "update-net-${System.currentTimeMillis()}", type = "NORMAL")
                )
                try {
                    api.updateServer(serverId, UpdateServerRequest(networkId = network.id))
                    val server = api.getServer(serverId)
                    server.networkId shouldBe network.id
                } finally {
                    runCatching { api.deleteServer(serverId) }
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
                val helper = ServerHelper(api)
                val serverId = helper.createTestServer(nodeId)
                try {
                    val original = api.getServer(serverId)
                    val originalName = original.name

                    api.updateServer(serverId, UpdateServerRequest(description = "only description"))
                    val updated = api.getServer(serverId)
                    updated.description shouldBe "only description"
                    updated.name shouldBe originalName
                } finally {
                    runCatching { api.deleteServer(serverId) }
                }
            }

            should("update is idempotent") {
                val helper = ServerHelper(api)
                val serverId = helper.createTestServer(nodeId)
                try {
                    api.updateServer(serverId, UpdateServerRequest(displayName = "idempotent-name"))
                    api.updateServer(serverId, UpdateServerRequest(displayName = "idempotent-name"))
                    val server = api.getServer(serverId)
                    server.displayName shouldBe "idempotent-name"
                } finally {
                    runCatching { api.deleteServer(serverId) }
                }
            }
        }
    }
}
