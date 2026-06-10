package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.MigrateRequest
import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.ServerHelper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.openapitools.client.infrastructure.ClientException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MigrationSecurityTest : BaseSystemTest() {

    private val wsClient = OkHttpClient.Builder().build()

    init {
        context("Migration validation") {

            val helper = ServerHelper(api)
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
                helper.awaitStatus(serverId, "HEALTHY")
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
                } finally {
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
                migrations["migrations"].orEmpty().shouldBeEmpty()
            }

            should("get non-existent migration returns 404") {
                shouldThrow<ClientException> {
                    api.getMigration("00000000-0000-0000-0000-000000000000")
                }.statusCode shouldBe 404
            }
        }

        context("Migration WebSocket auth") {

            should("connects to non-existent migration events without auth") {
                val url = "${wsBaseUrl}/api/migrations/00000000-0000-0000-0000-000000000000/events"
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
                // No auth required - endpoint just checks migration exists
                // Non-existent migration should close normally (1000)
                closeCode shouldBe 1000
            }

            should("migration WS does not require JWT or ticket") {
                // Connect without any auth headers or tickets
                val url = "${wsBaseUrl}/api/migrations/00000000-0000-0000-0000-000000000000/events"
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
                // The WS endpoint accepts connections from unauthenticated clients
                // and only checks if the migration exists - this is a known security gap
                closeCode shouldBe 1000
            }
        }
    }

    private val wsBaseUrl: String
        get() = masterApiUrl.replace("http://", "ws://")

    private fun request(url: String): Request = Request.Builder()
        .url(url)
        .build()
}
