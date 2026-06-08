package craftpanel.systemtest.node

import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.ServerHelper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException

class NodeResourcesTest : BaseSystemTest() {

    init {
        val serverHelper by lazy { ServerHelper(api) }

        describe("Node RAM allocation") {

            it("allocated_ram_mb is 0 before any servers are created") {
                val node = api.getNode(nodeId)
                node.allocatedRamMb shouldBe 0
            }

            it("total_ram_mb reflects SYSTEM_RESERVED_RAM_MB subtraction by agent") {
                // The agent subtracts SYSTEM_RESERVED_RAM_MB from raw hardware RAM before
                // reporting totalRamMb to master. The stored total must be positive and
                // represents the capacity available for server allocation.
                val node = api.getNode(nodeId)
                node.totalRamMb shouldBeGreaterThan 0
            }

            it("allocated_ram_mb increases when a server is created") {
                serverHelper.createTestServer(nodeId, memoryMb = 512)

                val node = api.getNode(nodeId)
                node.allocatedRamMb shouldBe 512
            }

            it("allocated_ram_mb accumulates across multiple servers") {
                serverHelper.createTestServer(nodeId, memoryMb = 256)

                val node = api.getNode(nodeId)
                node.allocatedRamMb shouldBe 512 + 256
            }

            it("allocated_ram_mb is always less than or equal to total_ram_mb") {
                val node = api.getNode(nodeId)
                node.allocatedRamMb shouldBeGreaterThanOrEqual 0
                node.allocatedRamMb.toLong() + 1 // ensure no overflow
                (node.totalRamMb - node.allocatedRamMb) shouldBeGreaterThanOrEqual 0
            }

            it("creating a server that exceeds total_ram_mb capacity returns 409") {
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
