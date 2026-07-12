package craftpanel.systemtest.node

import craftpanel.systemtest.client.model.NodeStatus
import craftpanel.systemtest.client.model.ServerStatus
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.core.annotation.Isolate
import io.kotest.core.annotation.Tags
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty

@Isolate
@Tags("Node")
class MultiNodeTest : BaseSystemTest() {


    private val serverIds = mutableListOf<String>()

    init {
        afterSpec {
            serverIds.forEach { id ->
                runCatching { api.stopServer(id) }
                helper.awaitStoppedOrGone(id)
                runCatching { api.deleteServer(id) }
            }
        }

        context("Multi-node operations") {

            should("listNodes returns both agents") {
                val nodes = api.listNodes()
                nodes.shouldHaveSize(2)
                nodes.all { it.status == NodeStatus.ACTIVE } shouldBe true
                nodes.map { it.id }
                    .distinct()
                    .shouldHaveSize(2)
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

                val serverA = helper.createTestServer(nodeA)
                    .also { serverIds.add(it) }
                val serverB = helper.createTestServer(nodeB)
                    .also { serverIds.add(it) }

                api.getServer(serverA).nodeId shouldBe nodeA
                api.getServer(serverB).nodeId shouldBe nodeB

                // listServers() is global; under a shared stack other specs' servers
                // may be present. Assert containment of the two we created, not count.
                val allServers = api.listServers()
                allServers.map { it.id }
                    .shouldContainAll(listOf(serverA, serverB))
            }

            should("can start and stop servers on both nodes") {
                val nodes = api.listNodes()
                val nodeA = nodes[0].id
                val nodeB = nodes[1].id

                val serverA = helper.createTestServer(nodeA)
                    .also { serverIds.add(it) }
                val serverB = helper.createTestServer(nodeB)
                    .also { serverIds.add(it) }

                api.startServer(serverA)
                helper.awaitStatus(serverA, ServerStatus.HEALTHY, timeoutMs = 180_000)
                api.startServer(serverB)
                helper.awaitStatus(serverB, ServerStatus.HEALTHY, timeoutMs = 180_000)

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
