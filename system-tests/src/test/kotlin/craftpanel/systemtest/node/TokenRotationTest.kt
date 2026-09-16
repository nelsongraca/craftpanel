package craftpanel.systemtest.node

import craftpanel.systemtest.client.model.NodeStatus
import craftpanel.systemtest.harness.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Isolate
import io.kotest.core.annotation.Tags
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import org.openapitools.client.infrastructure.ClientException

@Isolate
@Tags("Node")
class TokenRotationTest : BaseSystemTest() {

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

        context("Token rotation") {

            should("rotate the token, return a new key, and keep the node ACTIVE") {
                val response = api.rotateNodeToken(agentNodeId)
                response.nodeKey.shouldNotBeEmpty()

                val node = api.getNode(agentNodeId)
                node.status shouldBe NodeStatus.ACTIVE
            }

            should("rotate the token twice") {
                val first = api.rotateNodeToken(agentNodeId)
                first.nodeKey.shouldNotBeEmpty()

                val second = api.rotateNodeToken(agentNodeId)
                second.nodeKey.shouldNotBeEmpty()
            }

            should("return 404 for a non-existent node") {
                shouldThrow<ClientException> {
                    api.rotateNodeToken("00000000-0000-0000-0000-000000000000")
                }.statusCode shouldBe 404
            }

            should("reject an agent with the old key after rotation") {
                val response = api.rotateNodeToken(agentNodeId)
                response.nodeKey.shouldNotBeEmpty()

                val helper = NodeCleanupHelper(SharedStack.dockerClient)
                helper.restartContainer(agentContainerId)

                val exited = helper.waitForContainerStop(agentContainerId, timeoutMs = 30_000)
                exited shouldBe true
                helper.isContainerRunning(agentContainerId) shouldBe false

                val exitCode = helper.getContainerExitCode(agentContainerId)
                exitCode.shouldNotBeNull()
                exitCode shouldBe 1
            }
        }
    }
}
