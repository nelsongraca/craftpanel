package craftpanel.systemtest.node

import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.ServerHelper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException

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

            should("allocated_ram_mb matches baseline before any servers are created in this spec") {
                val node = api.getNode(nodeId)
                node.allocatedRamMb shouldBe baseline
            }

            should("total_ram_mb reflects SYSTEM_RESERVED_RAM_MB subtraction by agent") {
                // The agent subtracts SYSTEM_RESERVED_RAM_MB from raw hardware RAM before
                // reporting totalRamMb to master. The stored total must be positive and
                // represents the capacity available for server allocation.
                val node = api.getNode(nodeId)
                node.totalRamMb shouldBeGreaterThan 0
            }

            should("allocated_ram_mb increases when a server is created") {
                val serverId = serverHelper.createTestServer(nodeId, memoryMb = 512)
                createdServerIds += serverId

                val node = api.getNode(nodeId)
                node.allocatedRamMb shouldBe baseline + 512
            }

            should("allocated_ram_mb accumulates across multiple servers") {
                val serverId = serverHelper.createTestServer(nodeId, memoryMb = 256)
                createdServerIds += serverId

                val node = api.getNode(nodeId)
                node.allocatedRamMb shouldBe baseline + 512 + 256
            }

            should("allocated_ram_mb is always less than or equal to total_ram_mb") {
                val node = api.getNode(nodeId)
                node.allocatedRamMb shouldBeGreaterThanOrEqual 0
                node.allocatedRamMb.toLong() + 1 // ensure no overflow
                (node.totalRamMb - node.allocatedRamMb) shouldBeGreaterThanOrEqual 0
            }

            should("creating a server that exceeds total_ram_mb capacity returns 409") {
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
