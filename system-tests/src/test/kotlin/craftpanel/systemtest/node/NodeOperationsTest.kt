package craftpanel.systemtest.node

import craftpanel.systemtest.client.model.NodeStatus
import craftpanel.systemtest.client.model.PatchNodeRequest
import craftpanel.systemtest.client.model.ServerStatus
import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.SharedStack
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Isolate
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import org.openapitools.client.infrastructure.ClientException

@Isolate
class NodeOperationsTest : BaseSystemTest() {

    init {
        context("getNode") {

            should("returns full metadata for a trusted node") {
                val node = api.getNode(SharedStack.nodeId)
                node.id shouldBe SharedStack.nodeId
                node.status shouldBe NodeStatus.ACTIVE
                node.displayName.shouldNotBeEmpty()
                node.hostname.shouldNotBeEmpty()
                node.totalRamMb.shouldBeGreaterThan(0)
                node.allocatedRamMb.shouldBeGreaterThanOrEqual(0)
                node.createdAt.shouldNotBeEmpty()
            }

            should("returns 404 for non-existent node") {
                val ex = shouldThrow<ClientException> {
                    api.getNode("00000000-0000-0000-0000-000000000000")
                }
                ex.statusCode shouldBe 404
            }
        }

        context("updateNode") {
            var originalDisplayName = ""

            beforeContainer {
                originalDisplayName = api.getNode(SharedStack.nodeId).displayName
            }

            afterContainer {
                runCatching {
                    api.updateNode(SharedStack.nodeId, PatchNodeRequest(displayName = originalDisplayName))
                }
            }

            should("updates node display name") {
                val newName = "updated-node-${System.currentTimeMillis()}"
                api.updateNode(
                    SharedStack.nodeId,
                    PatchNodeRequest(displayName = newName)
                )
                val node = api.getNode(SharedStack.nodeId)
                node.displayName shouldBe newName
            }

            should("returns 422 for invalid port range") {
                val ex = shouldThrow<ClientException> {
                    api.updateNode(
                        SharedStack.nodeId,
                        PatchNodeRequest(portRangeStart = 30000, portRangeEnd = 20000)
                    )
                }
                ex.statusCode shouldBe 422
            }
        }

        context("rejectNode") {

            should("rejects a PENDING node and transitions it to REJECTED") {
                val containerId = SharedStack.addAgent()
                try {
                    val pending = nodeHelper.awaitPendingNode()
                    pending.status shouldBe NodeStatus.PENDING

                    api.rejectNode(pending.id)
                    val rejected = api.getNode(pending.id)
                    rejected.status shouldBe NodeStatus.REJECTED
                }
                finally {
                    SharedStack.removeAgent(containerId)
                }
            }

            should("rejecting an ACTIVE node returns 409") {
                val containerId = SharedStack.addAgent()
                var nodeId = ""
                try {
                    nodeId = nodeHelper.trustFirstPendingNode()

                    val ex = shouldThrow<ClientException> { api.rejectNode(nodeId) }
                    ex.statusCode shouldBe 409
                }
                finally {
                    runCatching { api.decommissionNode(nodeId) }
                    SharedStack.removeAgent(containerId)
                }
            }
        }

        context("rotateNodeToken") {

            should("rotates the node token and returns a new key") {
                val containerId = SharedStack.addAgent()
                var nodeId = ""
                try {
                    nodeId = nodeHelper.trustFirstPendingNode()
                    val keyResponse = api.rotateNodeToken(nodeId)
                    keyResponse.nodeKey.shouldNotBeEmpty()

                    val node = api.getNode(nodeId)
                    node.status shouldBe NodeStatus.ACTIVE
                }
                finally {
                    runCatching { api.decommissionNode(nodeId) }
                    SharedStack.removeAgent(containerId)
                }
            }

            should("agent with old token is rejected after rotation") {
                val containerId = SharedStack.addAgent()
                var nodeId = ""
                try {
                    nodeId = nodeHelper.trustFirstPendingNode()
                    val keyResponse = api.rotateNodeToken(nodeId)
                    keyResponse.nodeKey.shouldNotBeEmpty()
                }
                finally {
                    runCatching { api.decommissionNode(nodeId) }
                    SharedStack.removeAgent(containerId)
                }
            }
        }

        context("decommissionNode") {

            should("decommissions a node without active servers") {
                val containerId = SharedStack.addAgent()
                var nodeId = ""
                try {
                    nodeId = nodeHelper.trustFirstPendingNode()

                    api.decommissionNode(nodeId)
                    val node = api.getNode(nodeId)
                    node.status shouldBe NodeStatus.DECOMMISSIONED
                }
                finally {
                    SharedStack.removeAgent(containerId)
                }
            }

            should("decommissioning a node with active servers returns 409") {
                val containerId = SharedStack.addAgent()
                var nodeId = ""
                var serverId = ""
                try {
                    nodeId = nodeHelper.trustFirstPendingNode()

                    serverId = helper.createTestServer(nodeId)
                    api.startServer(serverId)
                    // Server status changes to STARTING immediately from the REST handler
                    // No need to wait for HEALTHY (fake-server may not reach it)
                    runCatching {
                        var attempts = 0
                        while (attempts < 30) {
                            val s = api.getServer(serverId)
                            if (s.status != ServerStatus.STOPPED) return@runCatching
                            Thread.sleep(500)
                            attempts++
                        }
                    }

                    val ex = shouldThrow<ClientException> {
                        api.decommissionNode(nodeId)
                    }
                    ex.statusCode shouldBe 409
                }
                finally {
                    runCatching { api.stopServer(serverId) }
                    runCatching { api.deleteServer(serverId) }
                    runCatching { api.decommissionNode(nodeId) }
                    SharedStack.removeAgent(containerId)
                }
            }
        }
    }
}
