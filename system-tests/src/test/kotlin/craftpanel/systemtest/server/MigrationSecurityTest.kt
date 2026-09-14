package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.MigrateRequest
import craftpanel.systemtest.client.model.ServerStatus
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import okhttp3.*
import org.openapitools.client.infrastructure.ClientException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Tags("ServerOps")
class MigrationSecurityTest : BaseSystemTest() {

    private val wsClient = OkHttpClient.Builder()
        .build()

    init {
        context("Migration validation") {


            lateinit var serverId: String

            beforeEach {
                serverId = helper.createTestServer(nodeId)
            }

            afterEach {
                runCatching { api.stopServer(serverId) }
                helper.awaitStoppedOrGone(serverId)
                runCatching { api.deleteServer(serverId) }
            }

            should("start migration of running server returns 409") {
                api.startServer(serverId)
                helper.awaitStatus(serverId, ServerStatus.HEALTHY)
                try {
                    shouldThrow<ClientException> {
                        api.startMigration(
                            serverId,
                            MigrateRequest(
                                targetNodeId = nodeId,
                                rsyncImage = "craftpanel-rsync",
                                playerWarningMessage = "migration in progress"
                            )
                        )
                    }.statusCode shouldBe 409
                }
                finally {
                    runCatching { api.stopServer(serverId) }
                    helper.awaitStoppedOrGone(serverId)
                }
            }

            should("start migration to non-existent node returns 404") {
                shouldThrow<ClientException> {
                    api.startMigration(
                        serverId,
                        MigrateRequest(
                            targetNodeId = "00000000-0000-0000-0000-000000000000",
                            rsyncImage = "craftpanel-rsync",
                            playerWarningMessage = "test"
                        )
                    )
                }.statusCode shouldBe 404
            }

            should("list migrations on clean server returns empty") {
                val migrations = api.listMigrations(serverId)
                migrations["migrations"].orEmpty()
                    .shouldBeEmpty()
            }

            should("get non-existent migration returns 404") {
                shouldThrow<ClientException> {
                    api.getMigration("00000000-0000-0000-0000-000000000000")
                }.statusCode shouldBe 404
            }
        }

        context("Migration WebSocket auth") {

            should("rejects connection without a ticket") {
                val url = "${wsBaseUrl}/api/migrations/00000000-0000-0000-0000-000000000000/events"
                wsCloseCode(url) shouldBe 1008
            }

            should("rejects connection with invalid ticket") {
                val url = "${wsBaseUrl}/api/migrations/00000000-0000-0000-0000-000000000000/events?ticket=invalid-fake-ticket"
                wsCloseCode(url) shouldBe 1008
            }

            should("non-existent migration with valid ticket closes normally") {
                val ticket = api.authWsTicket()
                val url = "${wsBaseUrl}/api/migrations/00000000-0000-0000-0000-000000000000/events?ticket=${ticket.ticket}"
                wsCloseCode(url) shouldBe 1000
            }
        }
    }

    private fun wsCloseCode(url: String): Int {
        val latch = CountDownLatch(1)
        var closeCode = -1

        wsClient.newWebSocket(request(url), object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                closeCode = code; latch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (closeCode == -1) closeCode = code; latch.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closeCode = response?.code ?: -1; latch.countDown()
            }
        })

        latch.await(5, TimeUnit.SECONDS)
        return closeCode
    }

    private val wsBaseUrl: String
        get() = masterApiUrl.replace("http://", "ws://")

    private fun request(url: String): Request = Request.Builder()
        .url(url)
        .build()
}
