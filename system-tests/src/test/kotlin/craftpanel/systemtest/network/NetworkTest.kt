package craftpanel.systemtest.network

import craftpanel.systemtest.client.model.CreateNetworkRequest
import craftpanel.systemtest.client.model.PatchNetworkRequest
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException

class NetworkTest : BaseSystemTest() {

    init {
        context("Network CRUD") {

            lateinit var createdNetworkId: String
            val networkName = "test-network-${System.currentTimeMillis()}"

            should("creates a network") {
                val network = api.createNetwork(
                    CreateNetworkRequest(
                        name = networkName
                    )
                )
                createdNetworkId = network.id
                network.name shouldBe networkName
            }

            should("lists networks including the new network") {
                val networks = api.listNetworks()
                networks.map { it.name } shouldContain networkName
            }

            should("gets network detail by ID") {
                val network = api.getNetwork(createdNetworkId)
                network.name shouldBe networkName
            }

            should("updates network name") {
                val newName = "renamed-network-${System.currentTimeMillis()}"
                api.updateNetwork(createdNetworkId, PatchNetworkRequest(name = newName))
                val network = api.getNetwork(createdNetworkId)
                network.name shouldBe newName
            }

            should("deletes a network") {
                api.deleteNetwork(createdNetworkId)
                val networks = api.listNetworks()
                networks.map { it.id } shouldNotContain createdNetworkId
            }

            should("creating a network with duplicate name returns 409") {
                val name = "unique-network-${System.currentTimeMillis()}"
                api.createNetwork(CreateNetworkRequest(name = name))
                val ex = shouldThrow<ClientException> {
                    api.createNetwork(CreateNetworkRequest(name = name))
                }
                ex.statusCode shouldBe 409
            }

            should("getting a non-existent network returns 404") {
                val ex = shouldThrow<ClientException> {
                    api.getNetwork("00000000-0000-0000-0000-000000000000")
                }
                ex.statusCode shouldBe 404
            }
        }
    }
}
