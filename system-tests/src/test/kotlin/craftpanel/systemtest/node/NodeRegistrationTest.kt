package craftpanel.systemtest.node

import craftpanel.systemtest.harness.AuthHelper
import craftpanel.systemtest.harness.NodeHelper
import craftpanel.systemtest.harness.SharedStack
import craftpanel.systemtest.client.api.DefaultApi
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.openapitools.client.infrastructure.ClientException

class NodeRegistrationTest : ShouldSpec() {

    private val api: DefaultApi by lazy { DefaultApi(basePath = SharedStack.masterApiUrl) }
    private var agentNodeId = ""
    private var agentContainerId = ""

    init {
        beforeSpec {
            AuthHelper(api).login()
            agentContainerId = SharedStack.addAgent()
        }

        afterSpec {
            runCatching { api.decommissionNode(agentNodeId) }
            SharedStack.removeAgent(agentContainerId)
        }

        context("Node registration") {
            var nodeId = ""

            should("agent registers and appears as PENDING before trust") {
                val node = NodeHelper(api).awaitPendingNode()
                nodeId = node.id
                agentNodeId = node.id
                node.status shouldBe "PENDING"
            }

            should("PENDING node stays PENDING after agent connects and sends state snapshot") {
                val node = api.getNode(nodeId)
                node.status shouldBe "PENDING"
            }

            should("PENDING node stays PENDING after agent container restart") {
                val docker = SharedStack.dockerClient
                docker.restartContainerCmd(agentContainerId)
                    .exec()

                kotlinx.coroutines.delay(5_000)
                val node = api.getNode(nodeId)
                node.status shouldNotBe "ACTIVE"
                node.status shouldBe "PENDING"
            }

            should("trusting a PENDING node transitions it to ACTIVE") {
                api.trustNode(nodeId)
                val active = NodeHelper(api).pollUntilActive(nodeId)
                active.status shouldBe "ACTIVE"
            }

            should("a second trust call on an ACTIVE node returns 409") {
                val ex = shouldThrow<ClientException> { api.trustNode(nodeId) }
                ex.statusCode shouldBe 409
            }
        }
    }
}
