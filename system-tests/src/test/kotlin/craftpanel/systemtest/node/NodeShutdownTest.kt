package craftpanel.systemtest.node

import craftpanel.systemtest.client.api.DefaultApi
import craftpanel.systemtest.harness.AuthHelper
import craftpanel.systemtest.harness.CraftPanelStack
import craftpanel.systemtest.harness.MultiNodeHelper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException

class NodeShutdownTest : DescribeSpec() {

    private val stack = CraftPanelStack()
    private val api: DefaultApi by lazy { DefaultApi(basePath = stack.masterApiUrl) }

    init {
        beforeSpec {
            stack.start(nodeCount = 1)
            AuthHelper(api).login()
            val ids = MultiNodeHelper(api).trustAllPendingNodes(1)
            stack.storeNodeIds(ids)
        }

        afterSpec {
            stack.stop()
        }

        describe("Node shutdown") {

            it("shuts down agent container") {
                val response = api.shutdownNode(stack.nodeId)
                response.message shouldBe "Shutdown command sent"

                val node = api.getNode(stack.nodeId)
                node.status shouldBe "ACTIVE"
            }

            it("shutdown non-existent node returns 404") {
                shouldThrow<ClientException> {
                    api.shutdownNode("00000000-0000-0000-0000-000000000000")
                }.statusCode shouldBe 404
            }
        }
    }
}