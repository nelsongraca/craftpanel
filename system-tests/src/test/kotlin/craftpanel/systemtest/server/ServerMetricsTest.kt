package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.ServerStatus
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.matchers.longs.shouldBeGreaterThan
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

            should("collect JVM heap metrics for a running JVM server") {
                // A HEALTHY itzg server runs a HotSpot JVM; the agent should attach via jcmd/jstat
                // and surface a heap sample. Poll until a sample lands (the first metric tick may
                // race the JVM becoming attachable).
                val deadline = Instant.now()
                    .plus(Duration.ofMinutes(2))
                var latest = emptyList<Long>()
                while (Instant.now() < deadline) {
                    val series = api.getServerMetrics(serverId, fiveMinutesAgo(), now()).series
                    latest = (series.heapMaxBytes ?: emptyList()).map { it.v }
                    if (latest.isNotEmpty()) break
                    Thread.sleep(2_000)
                }

                (latest.isEmpty()) shouldBe false
                (latest.max() ?: 0L) shouldBeGreaterThan 0L
            }

            should("not collect JVM metrics when the server has them disabled") {
                val disabledId = helper.createTestServer(nodeId, jvmMetricsEnabled = false)
                try {
                    api.startServer(disabledId)
                    helper.awaitStatus(disabledId, ServerStatus.HEALTHY)
                    // Give the metrics pump a few ticks to (not) emit a JVM sample.
                    Thread.sleep(10_000)

                    val series = api.getServerMetrics(disabledId, fiveMinutesAgo(), now()).series
                    (series.heapUsedBytes ?: emptyList()) shouldBe emptyList()
                    (series.heapMaxBytes ?: emptyList()) shouldBe emptyList()
                    (series.nonHeapUsedBytes ?: emptyList()) shouldBe emptyList()
                } finally {
                    runCatching { api.stopServer(disabledId) }
                    helper.awaitStoppedOrGone(disabledId)
                    runCatching { api.deleteServer(disabledId) }
                }
            }

            should("return empty JVM series for a stopped server") {
                val metrics = api.getServerMetrics(serverId2, fiveMinutesAgo(), now())

                (metrics.series.heapUsedBytes ?: emptyList()) shouldBe emptyList()
                (metrics.series.heapMaxBytes ?: emptyList()) shouldBe emptyList()
                (metrics.series.nonHeapUsedBytes ?: emptyList()) shouldBe emptyList()
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
