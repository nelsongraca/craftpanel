package craftpanel.systemtest.node

import craftpanel.systemtest.client.api.DefaultApi
import craftpanel.systemtest.harness.AuthHelper
import craftpanel.systemtest.harness.CraftPanelStack
import craftpanel.systemtest.harness.MultiNodeHelper
import craftpanel.systemtest.harness.NodeCleanupHelper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeFalse
import org.openapitools.client.infrastructure.ClientException

class NodeShutdownTest : DescribeSpec() {

    init {
        describe("Node shutdown") {

            val stack = CraftPanelStack()
            val api: DefaultApi by lazy { DefaultApi(basePath = stack.masterApiUrl) }

            beforeSpec {
                stack.start(nodeCount = 1)
                AuthHelper(api).login()
                val ids = MultiNodeHelper(api).trustAllPendingNodes(1)
                stack.storeNodeIds(ids)
            }

            afterSpec {
                stack.stop()
            }

            it("shuts down agent container") {
                val response = api.shutdownNode(stack.nodeId)
                response.message shouldBe "Shutdown command sent"

                val cleanup = NodeCleanupHelper(stack.dockerClient)
                val stopped = cleanup.waitForContainerStop(stack.agentContainerId, timeoutMs = 15_000)

                stopped shouldBe true
                cleanup.isContainerRunning(stack.agentContainerId).shouldBeFalse()
            }

            it("shutdown non-existent node returns 404") {
                shouldThrow<ClientException> {
                    api.shutdownNode("00000000-0000-0000-0000-000000000000")
                }.statusCode shouldBe 404
            }
        }
    }
}