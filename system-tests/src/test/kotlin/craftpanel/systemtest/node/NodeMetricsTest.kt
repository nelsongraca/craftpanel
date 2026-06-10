package craftpanel.systemtest.node

import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.openapitools.client.infrastructure.ClientException

class NodeMetricsTest : BaseSystemTest() {

    init {
        describe("Node metrics") {

            it("returns metric structure for trusted node") {
                val metrics = api.getNodeMetrics(nodeId)
                metrics.timestamps.shouldNotBe(null)
                metrics.cpuPercent.shouldNotBe(null)
                metrics.ramUsedMb.shouldNotBe(null)
                metrics.ramTotalMb.shouldNotBe(null)
                metrics.diskUsedBytes.shouldNotBe(null)
                metrics.diskTotalBytes.shouldNotBe(null)
                metrics.netInBytes.shouldNotBe(null)
                metrics.netOutBytes.shouldNotBe(null)
                metrics.timestamps.size shouldBe metrics.cpuPercent.size
            }

            it("metric arrays have matching sizes") {
                val metrics = api.getNodeMetrics(nodeId)
                val n = metrics.timestamps.size
                if (n > 0) {
                    metrics.cpuPercent.size shouldBe n
                    metrics.ramUsedMb.size shouldBe n
                    metrics.ramTotalMb.size shouldBe n
                    metrics.diskUsedBytes.size shouldBe n
                    metrics.diskTotalBytes.size shouldBe n
                }
            }

            it("returns 404 for non-existent node") {
                shouldThrow<ClientException> {
                    api.getNodeMetrics("00000000-0000-0000-0000-000000000000")
                }.statusCode shouldBe 404
            }
        }
    }
}
