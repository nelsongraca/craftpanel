package craftpanel.systemtest.node

import craftpanel.systemtest.harness.AuthHelper
import craftpanel.systemtest.harness.CraftPanelStack
import craftpanel.systemtest.harness.NodeHelper
import craftpanel.systemtest.client.api.DefaultApi
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.delay
import org.openapitools.client.infrastructure.ClientException

class NodeRegistrationTest : DescribeSpec() {

    private val api: DefaultApi by lazy { DefaultApi(basePath = CraftPanelStack.masterApiUrl) }

    init {
        beforeSpec {
            CraftPanelStack.start()
            AuthHelper(api).login()
        }

        afterSpec {
            CraftPanelStack.stop()
        }

        describe("Node registration") {
            var nodeId = ""

            it("agent registers and appears as PENDING before trust") {
                val node = NodeHelper(api).awaitPendingNode()
                nodeId = node.id
                node.status shouldBe "PENDING"
            }

            it("PENDING node stays PENDING after agent connects and sends state snapshot") {
                // By the time the stack is ready the agent has already sent NodeStateSnapshot.
                // Reconciliation must NOT auto-promote a PENDING node to ACTIVE.
                val node = api.getNode(nodeId)
                node.status shouldBe "PENDING"
            }

            it("PENDING node stays PENDING after agent container restart") {
                val docker = CraftPanelStack.dockerClient
                val containerId = CraftPanelStack.agentContainerId

                docker.restartContainerCmd(containerId)
                    .exec()

                // Wait for agent to reconnect and resend NodeStateSnapshot (up to 30s)
                val deadline = System.currentTimeMillis() + 30_000
                while (System.currentTimeMillis() < deadline) {
                    delay(500)
                    // If the bug is present the REST status would still be PENDING (DB is correct),
                    // but the WebSocket nodeStatusFlow would incorrectly emit ACTIVE.
                    // This assertion guards the REST/DB layer.
                    val node = api.getNode(nodeId)
                    node.status shouldNotBe "ACTIVE"
                    node.status shouldBe "PENDING"
                }
            }

            it("trusting a PENDING node transitions it to ACTIVE") {
                api.trustNode(nodeId)
                val active = NodeHelper(api).pollUntilActive(nodeId)
                active.status shouldBe "ACTIVE"
            }

            it("a second trust call on an ACTIVE node returns 409") {
                val ex = shouldThrow<ClientException> { api.trustNode(nodeId) }
                ex.statusCode shouldBe 409
            }
        }
    }
}
