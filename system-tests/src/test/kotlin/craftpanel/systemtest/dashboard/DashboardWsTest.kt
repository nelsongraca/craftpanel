package craftpanel.systemtest.dashboard

import com.google.gson.JsonParser
import craftpanel.systemtest.client.model.ServerStatus
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import okhttp3.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DashboardWsTest : BaseSystemTest() {

    private val wsClient = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    init {
        context("Dashboard WebSocket") {

            should("connects with valid ticket and receives SNAPSHOT event") {
                val ticket = api.authWsTicket()
                val url = "$wsBaseUrl/api/ws?ticket=${ticket.ticket}"
                val latch = CountDownLatch(1)
                var messageType: String? = null

                val ws = wsClient.newWebSocket(
                    Request.Builder()
                        .url(url)
                        .build(),
                    object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val json = JsonParser.parseString(text).asJsonObject
                            messageType = json.get("type")?.asString
                            latch.countDown()
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            latch.countDown()
                        }
                    }
                )

                latch.await(10, TimeUnit.SECONDS)
                messageType shouldBe "snapshot"
                ws.close(1000, "test done")
            }

            should("snapshot payload has expected structure") {
                val ticket = api.authWsTicket()
                val url = "$wsBaseUrl/api/ws?ticket=${ticket.ticket}"
                val latch = CountDownLatch(1)
                var servers: com.google.gson.JsonArray? = null
                var nodes: com.google.gson.JsonArray? = null

                val ws = wsClient.newWebSocket(
                    Request.Builder()
                        .url(url)
                        .build(),
                    object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val json = JsonParser.parseString(text).asJsonObject
                            val payload = json.getAsJsonObject("payload")
                            servers = payload?.getAsJsonArray("servers")
                            nodes = payload?.getAsJsonArray("nodes")
                            latch.countDown()
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            latch.countDown()
                        }
                    }
                )

                latch.await(10, TimeUnit.SECONDS)
                servers shouldNotBe null
                nodes shouldNotBe null
                ws.close(1000, "test done")
            }

            should("rejects connection with invalid ticket") {
                val url = "$wsBaseUrl/api/ws?ticket=invalid-fake-ticket"
                val latch = CountDownLatch(1)
                var closeCode = -1

                wsClient.newWebSocket(
                    Request.Builder()
                        .url(url)
                        .build(),
                    object : WebSocketListener() {
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            closeCode = code
                            latch.countDown()
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            if (closeCode == -1) closeCode = code
                            latch.countDown()
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            closeCode = response?.code ?: -1
                            latch.countDown()
                        }
                    }
                )

                latch.await(5, TimeUnit.SECONDS)
                closeCode shouldBe 1008
            }

            should("emits SERVER_STATUS events across start and stop") {
                // One server drives both the start (HEALTHY) and stop (STOPPED) assertions on a
                // single WS connection, saving an extra create + start cycle vs. two separate tests.
                val serverId = helper.createTestServer(nodeId)
                try {
                    val ticket = api.authWsTicket()
                    val url = "$wsBaseUrl/api/ws?ticket=${ticket.ticket}"
                    val healthyLatch = CountDownLatch(1)
                    val stoppedLatch = CountDownLatch(1)
                    val seenStatuses = mutableListOf<String>()

                    val ws = wsClient.newWebSocket(
                        Request.Builder()
                            .url(url)
                            .build(),
                        object : WebSocketListener() {
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                val json = JsonParser.parseString(text).asJsonObject
                                if (json.get("type")?.asString == "server.status") {
                                    val payload = json.getAsJsonObject("payload")
                                    if (payload?.get("server_id")?.asString == serverId) {
                                        val status = payload.get("status").asString
                                        seenStatuses.add(status)
                                        if (status == "HEALTHY") healthyLatch.countDown()
                                        if (status == "STOPPED") stoppedLatch.countDown()
                                    }
                                }
                            }
                        }
                    )

                    // Wait for initial SNAPSHOT
                    Thread.sleep(1000)

                    api.startServer(serverId)
                    helper.awaitStatus(serverId, ServerStatus.HEALTHY)
                    healthyLatch.await(15, TimeUnit.SECONDS)
                    seenStatuses.shouldNotBeEmpty()

                    api.stopServer(serverId)
                    helper.awaitStoppedOrGone(serverId)
                    stoppedLatch.await(15, TimeUnit.SECONDS)
                    seenStatuses shouldContain "STOPPED"

                    ws.close(1000, "test done")
                } finally {
                    runCatching { api.stopServer(serverId) }
                    helper.awaitStoppedOrGone(serverId)
                    runCatching { api.deleteServer(serverId) }
                }
            }
        }
    }

    private val wsBaseUrl: String
        get() = masterApiUrl.replace("http://", "ws://")
}
