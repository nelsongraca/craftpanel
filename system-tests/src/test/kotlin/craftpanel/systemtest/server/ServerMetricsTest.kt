package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.ServerStatus
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.openapitools.client.infrastructure.ClientException
import java.time.Duration
import java.time.Instant

@Tags("ServerCore")
class ServerMetricsTest : BaseSystemTest() {

    private fun now() = Instant.now()
        .toString()

    private fun fiveMinutesAgo() = Instant.now()
        .minus(Duration.ofMinutes(5))
        .toString()

    init {

        lateinit var serverId: String
        lateinit var serverId2: String

        beforeSpec {
            serverId = helper.createTestServer(nodeId)
            api.startServer(serverId)
            helper.awaitStatus(serverId, ServerStatus.HEALTHY)
            serverId2 = helper.createTestServer(nodeId)
        }
        afterSpec {
            runCatching { api.stopServer(serverId) }
            helper.awaitStoppedOrGone(serverId)
            runCatching { api.deleteServer(serverId) }
            runCatching { api.deleteServer(serverId2) }
        }

        context("Server container metrics") {

            should("return metric series for a running server") {
                val metrics = api.getServerMetrics(serverId, fiveMinutesAgo(), now())

                metrics.serverId shouldBe serverId
                metrics.series shouldNotBe null
            }

            should("return empty metrics for a stopped server") {
                val metrics = api.getServerMetrics(serverId2, fiveMinutesAgo(), now())

                metrics.serverId shouldBe serverId2
                metrics.series.cpuPercent shouldBe emptyList()
                metrics.series.ramUsedMb shouldBe emptyList()
                metrics.series.netInBytes shouldBe emptyList()
                metrics.series.netOutBytes shouldBe emptyList()
            }

            should("return 404 for a non-existent server") {
                val ex = shouldThrow<ClientException> {
                    api.getServerMetrics(
                        "00000000-0000-0000-0000-000000000000",
                        fiveMinutesAgo(),
                        now()
                    )
                }
                ex.statusCode shouldBe 404
            }

            should("return 400 when the from query parameter is missing") {
                val ex = shouldThrow<ClientException> {
                    api.getServerMetrics(serverId2, "", now())
                }
                ex.statusCode shouldBe 400
            }
        }
    }
}
