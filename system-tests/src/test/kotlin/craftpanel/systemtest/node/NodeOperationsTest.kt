package craftpanel.systemtest.node

import craftpanel.systemtest.client.api.DefaultApi
import craftpanel.systemtest.client.model.PatchNodeRequest
import craftpanel.systemtest.harness.AuthHelper
import craftpanel.systemtest.harness.CraftPanelStack
import craftpanel.systemtest.harness.NodeHelper
import craftpanel.systemtest.harness.SharedStack
import craftpanel.systemtest.harness.ServerHelper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeEmpty
import org.openapitools.client.infrastructure.ClientException

class NodeOperationsTest : DescribeSpec() {

    private val sharedApi: DefaultApi by lazy {
        DefaultApi(basePath = SharedStack.masterApiUrl)
    }

    init {
        beforeSpec {
            AuthHelper(sharedApi).login()
        }

        describe("getNode") {

            it("returns full metadata for a trusted node") {
                val node = sharedApi.getNode(SharedStack.nodeId)
                node.id shouldBe SharedStack.nodeId
                node.status shouldBe "ACTIVE"
                node.displayName.shouldNotBeEmpty()
                node.hostname.shouldNotBeEmpty()
                node.totalRamMb.shouldBeGreaterThan(0)
                node.allocatedRamMb.shouldBeGreaterThanOrEqual(0)
                node.createdAt.shouldNotBeEmpty()
            }

            it("returns 404 for non-existent node") {
                val ex = shouldThrow<ClientException> {
                    sharedApi.getNode("00000000-0000-0000-0000-000000000000")
                }
                ex.statusCode shouldBe 404
            }
        }

        describe("rejectNode") {
            val stack = CraftPanelStack()

            beforeTest {
                stack.start()
                AuthHelper(DefaultApi(basePath = stack.masterApiUrl)).login()
            }

            afterTest {
                stack.stop()
            }

            it("rejects a PENDING node and transitions it to REJECTED") {
                val api = DefaultApi(basePath = stack.masterApiUrl)
                AuthHelper(api).login()
                val pending = NodeHelper(api).awaitPendingNode()
                pending.status shouldBe "PENDING"

                api.rejectNode(pending.id)
                val rejected = api.getNode(pending.id)
                rejected.status shouldBe "REJECTED"
            }

            it("rejecting an ACTIVE node returns 409") {
                val api = DefaultApi(basePath = stack.masterApiUrl)
                AuthHelper(api).login()
                val nodeId = NodeHelper(api).trustFirstPendingNode()

                val ex = shouldThrow<ClientException> { api.rejectNode(nodeId) }
                ex.statusCode shouldBe 409
            }
        }

        describe("rotateNodeToken") {
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

            it("rotates the node token and returns a new key") {
                val api = DefaultApi(basePath = stack.masterApiUrl)
                AuthHelper(api).login()

                val keyResponse = api.rotateNodeToken(stack.nodeId)
                keyResponse.nodeKey.shouldNotBeEmpty()

                val node = api.getNode(stack.nodeId)
                node.status shouldBe "ACTIVE"
            }

            it("agent with old token is rejected after rotation") {
                val api = DefaultApi(basePath = stack.masterApiUrl)
                AuthHelper(api).login()

                val keyResponse = api.rotateNodeToken(stack.nodeId)
                keyResponse.nodeKey.shouldNotBeEmpty()
            }
        }

        describe("updateNode") {

            it("updates node display name") {
                val newName = "updated-node-${System.currentTimeMillis()}"
                sharedApi.updateNode(
                    SharedStack.nodeId,
                    PatchNodeRequest(displayName = newName)
                )
                val node = sharedApi.getNode(SharedStack.nodeId)
                node.displayName shouldBe newName
            }

            it("returns 422 for invalid port range") {
                val ex = shouldThrow<ClientException> {
                    sharedApi.updateNode(
                        SharedStack.nodeId,
                        PatchNodeRequest(portRangeStart = 30000, portRangeEnd = 20000)
                    )
                }
                ex.statusCode shouldBe 422
            }
        }

        describe("decommissionNode") {
            val stack = CraftPanelStack()

            beforeTest {
                stack.start()
                val api = DefaultApi(basePath = stack.masterApiUrl)
                AuthHelper(api).login()
            }

            afterTest {
                stack.stop()
            }

            it("decommissions a node without active servers") {
                val api = DefaultApi(basePath = stack.masterApiUrl)
                AuthHelper(api).login()

                val helper = NodeHelper(api)
                val nodeId = helper.trustFirstPendingNode()

                api.decommissionNode(nodeId)
                val node = api.getNode(nodeId)
                node.status shouldBe "DECOMMISSIONED"
            }
        }

        describe("decommissionNode guards") {
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

            it("decommissioning a node with active servers returns 409") {
                val api = DefaultApi(basePath = stack.masterApiUrl)
                AuthHelper(api).login()

                val helper = ServerHelper(api)
                val serverId = helper.createTestServer(stack.nodeId)
                try {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")

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
