package craftpanel.systemtest.server

import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.ServerHelper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.openapitools.client.infrastructure.ClientException
import java.time.Duration
import java.time.Instant

class ServerMetricsTest : BaseSystemTest() {

    private fun now() = Instant.now().toString()
    private fun fiveMinutesAgo() = Instant.now().minus(Duration.ofMinutes(5)).toString()

    init {
        context("Server container metrics") {

            should("running server returns metric series") {
                val helper = ServerHelper(api)
                val serverId = helper.createTestServer(nodeId)
                try {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")

                    val metrics = api.getServerMetrics(serverId, fiveMinutesAgo(), now())

                    metrics.serverId shouldBe serverId
                    metrics.series shouldNotBe null
                } finally {
                    runCatching { api.stopServer(serverId) }
                    helper.awaitStoppedOrGone(serverId)
                    runCatching { api.deleteServer(serverId) }
                }
            }

            should("stopped server returns empty metrics") {
                val helper = ServerHelper(api)
                val serverId = helper.createTestServer(nodeId)
                try {
                    val metrics = api.getServerMetrics(serverId, fiveMinutesAgo(), now())

                    metrics.serverId shouldBe serverId
                    metrics.series.cpuPercent shouldBe emptyList()
                    metrics.series.ramUsedMb shouldBe emptyList()
                    metrics.series.netInBytes shouldBe emptyList()
                    metrics.series.netOutBytes shouldBe emptyList()
                } finally {
                    runCatching { api.deleteServer(serverId) }
                }
            }

            should("non-existent server returns 404") {
                val ex = shouldThrow<ClientException> {
                    api.getServerMetrics(
                        "00000000-0000-0000-0000-000000000000",
                        fiveMinutesAgo(), now()
                    )
                }
                ex.statusCode shouldBe 404
            }

            should("missing from query parameter returns 400") {
                val helper = ServerHelper(api)
                val serverId = helper.createTestServer(nodeId)
                try {
                    val ex = shouldThrow<ClientException> {
                        api.getServerMetrics(serverId, "", now())
                    }
                    ex.statusCode shouldBe 400
                } finally {
                    runCatching { api.deleteServer(serverId) }
                }
            }
        }
    }
}
