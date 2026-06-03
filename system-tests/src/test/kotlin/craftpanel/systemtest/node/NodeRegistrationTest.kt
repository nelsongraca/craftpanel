package craftpanel.systemtest.node

import craftpanel.systemtest.harness.AuthHelper
import craftpanel.systemtest.harness.CraftPanelStack
import craftpanel.systemtest.harness.NodeHelper
import craftpanel.systemtest.client.api.DefaultApi
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
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
