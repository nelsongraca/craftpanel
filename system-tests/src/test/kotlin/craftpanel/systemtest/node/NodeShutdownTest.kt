package craftpanel.systemtest.node

import craftpanel.systemtest.client.api.DefaultApi
import craftpanel.systemtest.harness.AuthHelper
import craftpanel.systemtest.harness.NodeHelper
import craftpanel.systemtest.harness.SharedStack
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException

class NodeShutdownTest : ShouldSpec() {

    private val api: DefaultApi by lazy { DefaultApi(basePath = SharedStack.masterApiUrl) }
    private var agentNodeId = ""
    private var agentContainerId = ""

    init {
        beforeSpec {
            AuthHelper(api).login()
            agentContainerId = SharedStack.addAgent()
            agentNodeId = NodeHelper(api).trustFirstPendingNode()
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
                node.status shouldBe "ACTIVE"
            }

            should("shutdown non-existent node returns 404") {
                shouldThrow<ClientException> {
                    api.shutdownNode("00000000-0000-0000-0000-000000000000")
                }.statusCode shouldBe 404
            }
        }
    }
}
