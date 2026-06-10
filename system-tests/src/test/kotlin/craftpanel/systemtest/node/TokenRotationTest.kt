package craftpanel.systemtest.node

import craftpanel.systemtest.client.api.DefaultApi
import craftpanel.systemtest.harness.AuthHelper
import craftpanel.systemtest.harness.CraftPanelStack
import craftpanel.systemtest.harness.MultiNodeHelper
import craftpanel.systemtest.harness.NodeCleanupHelper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldNotBeEmpty
import org.openapitools.client.infrastructure.ClientException

class TokenRotationTest : ShouldSpec() {

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

        context("Token rotation") {

            should("rotates token and returns a new key, node stays ACTIVE") {
                val response = api.rotateNodeToken(stack.nodeId)
                response.nodeKey.shouldNotBeEmpty()

                val node = api.getNode(stack.nodeId)
                node.status shouldBe "ACTIVE"
            }

            should("can rotate token twice") {
                val first = api.rotateNodeToken(stack.nodeId)
                first.nodeKey.shouldNotBeEmpty()

                val second = api.rotateNodeToken(stack.nodeId)
                second.nodeKey.shouldNotBeEmpty()
            }

            should("returns 404 for non-existent node") {
                shouldThrow<ClientException> {
                    api.rotateNodeToken("00000000-0000-0000-0000-000000000000")
                }.statusCode shouldBe 404
            }

            should("agent with old key is rejected after rotation") {
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