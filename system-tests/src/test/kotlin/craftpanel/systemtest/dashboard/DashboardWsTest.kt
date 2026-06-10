package craftpanel.systemtest.dashboard

import com.google.gson.JsonParser
import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.ServerHelper
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldNotBeEmpty
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DashboardWsTest : BaseSystemTest() {

    private val wsClient = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    init {
        describe("Dashboard WebSocket") {

            it("connects with valid ticket and receives SNAPSHOT event") {
                val ticket = api.authWsTicket()
                val url = "${wsBaseUrl}/api/ws?ticket=${ticket.ticket}"
                val latch = CountDownLatch(1)
                var messageType: String? = null

                val ws = wsClient.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val json = JsonParser.parseString(text).asJsonObject
                        messageType = json.get("type")?.asString
                        latch.countDown()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        latch.countDown()
                    }
                })

                latch.await(10, TimeUnit.SECONDS)
                messageType shouldBe "SNAPSHOT"
                ws.close(1000, "test done")
            }

            it("snapshot payload has expected structure") {
                val ticket = api.authWsTicket()
                val url = "${wsBaseUrl}/api/ws?ticket=${ticket.ticket}"
                val latch = CountDownLatch(1)
                var servers: com.google.gson.JsonArray? = null
                var nodes: com.google.gson.JsonArray? = null

                val ws = wsClient.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
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
                })

                latch.await(10, TimeUnit.SECONDS)
                servers shouldNotBe null
                nodes shouldNotBe null
                ws.close(1000, "test done")
            }

            it("rejects connection with invalid ticket") {
                val url = "${wsBaseUrl}/api/ws?ticket=invalid-fake-ticket"
                val latch = CountDownLatch(1)
                var closeCode = -1

                wsClient.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
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
                closeCode shouldBe 1008
            }

            it("starting a server emits SERVER_STATUS events") {
                val helper = ServerHelper(api)
                val serverId = helper.createTestServer(nodeId)
                try {
                    val ticket = api.authWsTicket()
                    val url = "${wsBaseUrl}/api/ws?ticket=${ticket.ticket}"
                    val statusLatch = CountDownLatch(1)
                    val seenStatuses = mutableListOf<String>()

                    val ws = wsClient.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val json = JsonParser.parseString(text).asJsonObject
                            if (json.get("type")?.asString == "SERVER_STATUS") {
                                val payload = json.getAsJsonObject("payload")
                                if (payload?.get("id")?.asString == serverId) {
                                    val status = payload.get("status").asString
                                    seenStatuses.add(status)
                                    if (status == "HEALTHY") statusLatch.countDown()
                                }
                            }
                        }
                    })

                    // Wait for initial SNAPSHOT
                    Thread.sleep(1000)

                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")
                    statusLatch.await(15, TimeUnit.SECONDS)

                    seenStatuses.shouldNotBeEmpty()
                    ws.close(1000, "test done")
                } finally {
                    runCatching { api.stopServer(serverId) }
                    helper.awaitStoppedOrGone(serverId)
                    runCatching { api.deleteServer(serverId) }
                }
            }

            it("stopping a server emits SERVER_STATUS STOPPED") {
                val helper = ServerHelper(api)
                val serverId = helper.createTestServer(nodeId)
                try {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")

                    val ticket = api.authWsTicket()
                    val url = "${wsBaseUrl}/api/ws?ticket=${ticket.ticket}"
                    val stopLatch = CountDownLatch(1)
                    var stoppedReceived = false

                    val ws = wsClient.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val json = JsonParser.parseString(text).asJsonObject
                            if (json.get("type")?.asString == "SERVER_STATUS") {
                                val payload = json.getAsJsonObject("payload")
                                if (payload?.get("id")?.asString == serverId && payload.get("status")?.asString == "STOPPED") {
                                    stoppedReceived = true
                                    stopLatch.countDown()
                                }
                            }
                        }
                    })

                    Thread.sleep(1000)
                    api.stopServer(serverId)
                    helper.awaitStoppedOrGone(serverId)
                    stopLatch.await(15, TimeUnit.SECONDS)

                    stoppedReceived shouldBe true
                    ws.close(1000, "test done")
                } finally {
                    runCatching { api.deleteServer(serverId) }
                }
            }
        }
    }

    private val wsBaseUrl: String
        get() = masterApiUrl.replace("http://", "ws://")
}
