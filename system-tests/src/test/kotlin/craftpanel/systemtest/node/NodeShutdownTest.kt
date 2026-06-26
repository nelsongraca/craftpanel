package craftpanel.systemtest.node

import craftpanel.systemtest.client.model.NodeStatus
import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.SharedStack
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Isolate
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException

@Isolate
class NodeShutdownTest : BaseSystemTest() {

    private var agentNodeId = ""
    private var agentContainerId = ""

    init {
        beforeSpec {
            agentContainerId = SharedStack.addAgent()
            agentNodeId = nodeHelper.trustFirstPendingNode()
        }

        afterSpec {
            runCatching { api.decommissionNode(agentNodeId) }
            SharedStack.removeAgent(agentContainerId)
        }

        context("Node shutdown") {

            should("shuts down agent container") {
                val response = api.shutdownNode(agentNodeId)
                response.message shouldBe "Shutdown command sent"

                val node = api.getNode(agentNodeId)
                node.status shouldBe NodeStatus.ACTIVE
            }

            should("shutdown non-existent node returns 404") {
                shouldThrow<ClientException> {
                    api.shutdownNode("00000000-0000-0000-0000-000000000000")
                }.statusCode shouldBe 404
            }
        }
    }
}
