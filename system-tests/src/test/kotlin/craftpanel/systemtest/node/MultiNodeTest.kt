package craftpanel.systemtest.node

import craftpanel.systemtest.client.api.DefaultApi
import craftpanel.systemtest.harness.AuthHelper
import craftpanel.systemtest.harness.CraftPanelStack
import craftpanel.systemtest.harness.MultiNodeHelper
import craftpanel.systemtest.harness.ServerHelper
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty

class MultiNodeTest : ShouldSpec() {

    private val stack = CraftPanelStack()
    private val api: DefaultApi by lazy { DefaultApi(basePath = stack.masterApiUrl) }
    private val helper: ServerHelper by lazy { ServerHelper(api) }
    private val serverIds = mutableListOf<String>()

    init {
        beforeSpec {
            stack.start(nodeCount = 2)
            AuthHelper(api).login()
            val ids = MultiNodeHelper(api).trustAllPendingNodes(2)
            stack.storeNodeIds(ids)
        }

        afterSpec {
            serverIds.forEach { id ->
                runCatching { api.stopServer(id) }
                helper.awaitStoppedOrGone(id)
                runCatching { api.deleteServer(id) }
            }
            stack.stop()
        }

        context("Multi-node operations") {

            should("listNodes returns both agents") {
                val nodes = api.listNodes()
                nodes.shouldHaveSize(2)
                nodes.all { it.status == "ACTIVE" } shouldBe true
                nodes.map { it.id }.distinct().shouldHaveSize(2)
            }

            should("getNode returns correct metadata for each agent") {
                val nodes = api.listNodes()
                nodes.shouldHaveSize(2)

                for (node in nodes) {
                    val detailed = api.getNode(node.id)
                    detailed.id shouldBe node.id
                    detailed.hostname.shouldNotBeEmpty()
                    (detailed.totalRamMb > 0) shouldBe true
                }
            }

            should("can create servers on both nodes") {
                val nodes = api.listNodes()
                val nodeA = nodes[0].id
                val nodeB = nodes[1].id

                val serverA = helper.createTestServer(nodeA).also { serverIds.add(it) }
                val serverB = helper.createTestServer(nodeB).also { serverIds.add(it) }

                api.getServer(serverA).nodeId shouldBe nodeA
                api.getServer(serverB).nodeId shouldBe nodeB

                val allServers = api.listServers()
                allServers.map { it.id }.shouldHaveSize(2)
            }

            should("can start and stop servers on both nodes") {
                val nodes = api.listNodes()
                val nodeA = nodes[0].id
                val nodeB = nodes[1].id

                val serverA = helper.createTestServer(nodeA).also { serverIds.add(it) }
                val serverB = helper.createTestServer(nodeB).also { serverIds.add(it) }

                api.startServer(serverA)
                helper.awaitStatus(serverA, "HEALTHY")
                api.startServer(serverB)
                helper.awaitStatus(serverB, "HEALTHY")

                runCatching { api.stopServer(serverA) }
                runCatching { api.stopServer(serverB) }

                helper.awaitStoppedOrGone(serverA)
                helper.awaitStoppedOrGone(serverB)
            }

            should("node metrics available for both nodes") {
                val nodes = api.listNodes()
                for (node in nodes) {
                    val metrics = api.getNodeMetrics(node.id)
                    metrics.ramTotalMb.isNotEmpty() shouldBe true
                }
            }
        }
    }
}