package craftpanel.systemtest.node

import craftpanel.systemtest.client.api.DefaultApi
import craftpanel.systemtest.harness.AuthHelper
import craftpanel.systemtest.harness.CraftPanelStack
import craftpanel.systemtest.harness.MultiNodeHelper
import craftpanel.systemtest.harness.NodeCleanupHelper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldNotBeEmpty
import org.openapitools.client.infrastructure.ClientException

class TokenRotationTest : DescribeSpec() {

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

        describe("Token rotation") {

            it("rotates token and returns a new key, node stays ACTIVE") {
                val response = api.rotateNodeToken(stack.nodeId)
                response.nodeKey.shouldNotBeEmpty()

                val node = api.getNode(stack.nodeId)
                node.status shouldBe "ACTIVE"
            }

            it("can rotate token twice") {
                val first = api.rotateNodeToken(stack.nodeId)
                first.nodeKey.shouldNotBeEmpty()

                val second = api.rotateNodeToken(stack.nodeId)
                second.nodeKey.shouldNotBeEmpty()
            }

            it("returns 404 for non-existent node") {
                shouldThrow<ClientException> {
                    api.rotateNodeToken("00000000-0000-0000-0000-000000000000")
                }.statusCode shouldBe 404
            }

            it("agent with old key is rejected after rotation") {
                val response = api.rotateNodeToken(stack.nodeId)
                response.nodeKey.shouldNotBeEmpty()

                val helper = NodeCleanupHelper(stack.dockerClient)
                helper.restartContainer(stack.agentContainerId)

                val exited = helper.waitForContainerStop(stack.agentContainerId, timeoutMs = 10_000)
                exited shouldBe true
                helper.isContainerRunning(stack.agentContainerId) shouldBe false

                val exitCode = helper.getContainerExitCode(stack.agentContainerId)
                exitCode.shouldNotBeNull()
                exitCode shouldBe 1
            }
        }
    }
}