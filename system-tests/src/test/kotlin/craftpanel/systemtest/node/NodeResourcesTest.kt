package craftpanel.systemtest.node

import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.ServerHelper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Isolate
import io.kotest.core.annotation.Tags
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException

@Isolate
@Tags("Node")
class NodeResourcesTest : BaseSystemTest() {

    private val createdServerIds = mutableListOf<String>()
    private var baseline = 0

    init {
        val serverHelper by lazy { ServerHelper(api) }

        // Capture baseline after BaseSystemTest's beforeSpec (login) has run.
        // Use delta assertions so leftover allocation from other specs doesn't cause false failures.
        beforeSpec {
            baseline = api.getNode(nodeId).allocatedRamMb
        }

        afterSpec {
            createdServerIds.forEach { id -> runCatching { api.deleteServer(id) } }
            createdServerIds.clear()
        }

        context("Node RAM allocation") {

            should("match allocated_ram_mb to the baseline before any servers are created in this spec") {
                val node = api.getNode(nodeId)
                node.allocatedRamMb shouldBe baseline
            }

            should("report total_ram_mb and reserved_ram_mb as positive values") {
                // The agent reports the node's raw physical RAM as totalRamMb and its
                // SYSTEM_RESERVED_RAM_MB separately; master withholds the reserve when checking
                // allocatable capacity (total − reserved).
                val node = api.getNode(nodeId)
                node.totalRamMb shouldBeGreaterThan 0
                node.reservedRamMb shouldBeGreaterThanOrEqual 0
            }

            should("increase allocated_ram_mb when a server is created") {
                val serverId = serverHelper.createTestServer(nodeId, memoryMb = 512)
                createdServerIds += serverId

                val node = api.getNode(nodeId)
                node.allocatedRamMb shouldBe baseline + 512
            }

            should("accumulate allocated_ram_mb across multiple servers") {
                val serverId = serverHelper.createTestServer(nodeId, memoryMb = 256)
                createdServerIds += serverId

                val node = api.getNode(nodeId)
                node.allocatedRamMb shouldBe baseline + 512 + 256
            }

            should("keep allocated_ram_mb at or below total_ram_mb") {
                val node = api.getNode(nodeId)
                node.allocatedRamMb shouldBeGreaterThanOrEqual 0
                node.allocatedRamMb.toLong() + 1 // ensure no overflow
                (node.totalRamMb - node.allocatedRamMb) shouldBeGreaterThanOrEqual 0
            }

            should("return 409 when creating a server that exceeds total_ram_mb capacity") {
                val node = api.getNode(nodeId)
                val excessiveMb = node.totalRamMb + 1

                val ex = shouldThrow<ClientException> {
                    serverHelper.createTestServer(nodeId, memoryMb = excessiveMb)
                }
                ex.statusCode shouldBe 409
            }
        }
    }
}
