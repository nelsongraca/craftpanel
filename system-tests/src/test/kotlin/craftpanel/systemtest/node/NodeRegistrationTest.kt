package craftpanel.systemtest.node

import craftpanel.systemtest.client.model.NodeStatus
import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.SharedStack
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Isolate
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.openapitools.client.infrastructure.ClientException
import kotlin.time.Duration.Companion.milliseconds

@Isolate
class NodeRegistrationTest : BaseSystemTest() {

    private var agentNodeId = ""
    private var agentContainerId = ""

    init {
        beforeSpec {
            agentContainerId = SharedStack.addAgent()
        }

        afterSpec {
            runCatching { api.decommissionNode(agentNodeId) }
            SharedStack.removeAgent(agentContainerId)
        }

        context("Node registration") {
            var nodeId = ""

            should("agent registers and appears as PENDING before trust") {
                val node = nodeHelper.awaitPendingNode()
                nodeId = node.id
                agentNodeId = node.id
                node.status shouldBe NodeStatus.PENDING
            }

            should("PENDING node stays PENDING after agent connects and sends state snapshot") {
                val node = api.getNode(nodeId)
                node.status shouldBe NodeStatus.PENDING
            }

            should("PENDING node stays PENDING after agent container restart") {
                val docker = SharedStack.dockerClient
                docker.restartContainerCmd(agentContainerId)
                    .exec()

                kotlinx.coroutines.delay(5_000.milliseconds)
                val node = api.getNode(nodeId)
                node.status shouldNotBe "ACTIVE"
                node.status shouldBe NodeStatus.PENDING
            }

            should("trusting a PENDING node transitions it to ACTIVE") {
                api.trustNode(nodeId)
                val active = nodeHelper.pollUntilActive(nodeId)
                active.status shouldBe NodeStatus.ACTIVE
            }

            should("a second trust call on an ACTIVE node returns 409") {
                val ex = shouldThrow<ClientException> { api.trustNode(nodeId) }
                ex.statusCode shouldBe 409
            }
        }
    }
}
