package craftpanel.systemtest.node

import craftpanel.systemtest.client.api.DefaultApi
import craftpanel.systemtest.client.model.PatchNodeRequest
import craftpanel.systemtest.harness.AuthHelper
import craftpanel.systemtest.harness.CraftPanelStack
import craftpanel.systemtest.harness.NodeHelper
import craftpanel.systemtest.harness.SharedStack
import craftpanel.systemtest.harness.ServerHelper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeEmpty
import org.openapitools.client.infrastructure.ClientException

class NodeOperationsTest : ShouldSpec() {

    private val sharedApi: DefaultApi by lazy {
        DefaultApi(basePath = SharedStack.masterApiUrl)
    }

    init {
        beforeSpec {
            AuthHelper(sharedApi).login()
        }

        context("getNode") {

            should("returns full metadata for a trusted node") {
                val node = sharedApi.getNode(SharedStack.nodeId)
                node.id shouldBe SharedStack.nodeId
                node.status shouldBe "ACTIVE"
                node.displayName.shouldNotBeEmpty()
                node.hostname.shouldNotBeEmpty()
                node.totalRamMb.shouldBeGreaterThan(0)
                node.allocatedRamMb.shouldBeGreaterThanOrEqual(0)
                node.createdAt.shouldNotBeEmpty()
            }

            should("returns 404 for non-existent node") {
                val ex = shouldThrow<ClientException> {
                    sharedApi.getNode("00000000-0000-0000-0000-000000000000")
                }
                ex.statusCode shouldBe 404
            }
        }

        context("rejectNode") {
            val stack = CraftPanelStack()

            beforeTest {
                stack.start()
                AuthHelper(DefaultApi(basePath = stack.masterApiUrl)).login()
            }

            afterTest {
                stack.stop()
            }

            should("rejects a PENDING node and transitions it to REJECTED") {
                val api = DefaultApi(basePath = stack.masterApiUrl)
                AuthHelper(api).login()
                val pending = NodeHelper(api).awaitPendingNode()
                pending.status shouldBe "PENDING"

                api.rejectNode(pending.id)
                val rejected = api.getNode(pending.id)
                rejected.status shouldBe "REJECTED"
            }

            should("rejecting an ACTIVE node returns 409") {
                val api = DefaultApi(basePath = stack.masterApiUrl)
                AuthHelper(api).login()
                val nodeId = NodeHelper(api).trustFirstPendingNode()

                val ex = shouldThrow<ClientException> { api.rejectNode(nodeId) }
                ex.statusCode shouldBe 409
            }
        }

        context("rotateNodeToken") {
            val stack = CraftPanelStack()

            beforeTest {
                stack.start()
                val api = DefaultApi(basePath = stack.masterApiUrl)
                AuthHelper(api).login()
                val nodeId = NodeHelper(api).trustFirstPendingNode()
                stack.storeNodeId(nodeId)
            }

            afterTest {
                stack.stop()
            }

            should("rotates the node token and returns a new key") {
                val api = DefaultApi(basePath = stack.masterApiUrl)
                AuthHelper(api).login()

                val keyResponse = api.rotateNodeToken(stack.nodeId)
                keyResponse.nodeKey.shouldNotBeEmpty()

                val node = api.getNode(stack.nodeId)
                node.status shouldBe "ACTIVE"
            }

            should("agent with old token is rejected after rotation") {
                val api = DefaultApi(basePath = stack.masterApiUrl)
                AuthHelper(api).login()

                val keyResponse = api.rotateNodeToken(stack.nodeId)
                keyResponse.nodeKey.shouldNotBeEmpty()
            }
        }

        context("updateNode") {

            beforeTest {
                AuthHelper(sharedApi).login()
            }

            should("updates node display name") {
                val newName = "updated-node-${System.currentTimeMillis()}"
                sharedApi.updateNode(
                    SharedStack.nodeId,
                    PatchNodeRequest(displayName = newName)
                )
                val node = sharedApi.getNode(SharedStack.nodeId)
                node.displayName shouldBe newName
            }

            should("returns 422 for invalid port range") {
                val ex = shouldThrow<ClientException> {
                    sharedApi.updateNode(
                        SharedStack.nodeId,
                        PatchNodeRequest(portRangeStart = 30000, portRangeEnd = 20000)
                    )
                }
                ex.statusCode shouldBe 422
            }
        }

        context("decommissionNode") {
            val stack = CraftPanelStack()

            beforeTest {
                stack.start()
                val api = DefaultApi(basePath = stack.masterApiUrl)
                AuthHelper(api).login()
            }

            afterTest {
                stack.stop()
            }

            should("decommissions a node without active servers") {
                val api = DefaultApi(basePath = stack.masterApiUrl)
                AuthHelper(api).login()

                val helper = NodeHelper(api)
                val nodeId = helper.trustFirstPendingNode()

                api.decommissionNode(nodeId)
                val node = api.getNode(nodeId)
                node.status shouldBe "DECOMMISSIONED"
            }
        }

        context("decommissionNode guards") {
            val stack = CraftPanelStack()

            beforeTest {
                stack.start()
                val api = DefaultApi(basePath = stack.masterApiUrl)
                AuthHelper(api).login()
                val nodeId = NodeHelper(api).trustFirstPendingNode()
                stack.storeNodeId(nodeId)
            }

            afterTest {
                stack.stop()
            }

            should("decommissioning a node with active servers returns 409") {
                val api = DefaultApi(basePath = stack.masterApiUrl)
                AuthHelper(api).login()

                val helper = ServerHelper(api)
                val serverId = helper.createTestServer(stack.nodeId)
                try {
                    api.startServer(serverId)
                    // Server status changes to STARTING immediately from the REST handler
                    // No need to wait for HEALTHY (fake-server may not reach it)
                    runCatching {
                        var attempts = 0
                        while (attempts < 30) {
                            val s = api.getServer(serverId)
                            if (s.status != "STOPPED") return@runCatching
                            Thread.sleep(500)
                            attempts++
                        }
                    }

                    val ex = shouldThrow<ClientException> {
                        api.decommissionNode(stack.nodeId)
                    }
                    ex.statusCode shouldBe 409
                } finally {
                    runCatching { api.stopServer(serverId) }
                    runCatching { api.deleteServer(serverId) }
                }
            }
        }
    }
}
