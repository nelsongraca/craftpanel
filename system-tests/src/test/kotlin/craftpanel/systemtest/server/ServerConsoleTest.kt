package craftpanel.systemtest.server

import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.ServerHelper
import craftpanel.systemtest.harness.CraftPanelStack
import io.kotest.matchers.shouldBe
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ServerConsoleTest : BaseSystemTest() {

    private val wsClient = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    init {
        describe("Server console WebSocket") {

            describe("connection auth") {
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

                it("rejects connection without a ticket") {
                    val url = "${wsBaseUrl}/api/ws/console/${serverId}"
                    val request = Request.Builder()
                        .url(url)
                        .build()
                    val latch = CountDownLatch(1)
                    var closeCode = -1

                    wsClient.newWebSocket(request, object : WebSocketListener() {
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
                    })

                    latch.await(5, TimeUnit.SECONDS)
                    closeCode shouldBe 1008
                }

                it("connects with valid ticket") {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY", 60_000)
                    val ticket = api.authWsTicket()
                    val url = "${wsBaseUrl}/api/ws/console/${serverId}?ticket=${ticket.ticket}"
                    val latch = CountDownLatch(1)
                    var connected = false

                    val ws = wsClient.newWebSocket(request(url), object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            connected = true
                            latch.countDown()
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            latch.countDown()
                        }
                    })

                    latch.await(5, TimeUnit.SECONDS)
                    connected shouldBe true
                    ws.close(1000, "test done")
                }

                it("connecting to non-existent server closes normally") {
                    val ticket = api.authWsTicket()
                    val url = "${wsBaseUrl}/api/ws/console/00000000-0000-0000-0000-000000000000?ticket=${ticket.ticket}"
                    val latch = CountDownLatch(1)
                    var closeCode = -1

                    wsClient.newWebSocket(request(url), object : WebSocketListener() {
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
                    })

                    latch.await(5, TimeUnit.SECONDS)
                    closeCode shouldBe 1000
                }
            }

            describe("interactive session") {

                it("connects and sends a command") {
                    val helper = ServerHelper(api)
                    val serverId = helper.createTestServer(nodeId)
                    var ws: WebSocket? = null
                    try {
                        api.startServer(serverId)
                        helper.awaitStatus(serverId, "HEALTHY", 60_000)

                        val ticket = api.authWsTicket()
                        val url = "${wsBaseUrl}/api/ws/console/${serverId}?ticket=${ticket.ticket}"
                        val connectLatch = CountDownLatch(1)

                        ws = wsClient.newWebSocket(request(url), object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                connectLatch.countDown()
                            }
                        })

                        connectLatch.await(5, TimeUnit.SECONDS)

                        val sent = ws.send("list")
                        sent shouldBe true
                    }
                    finally {
                        ws?.close(1000, "test done")
                        runCatching { api.stopServer(serverId) }
                        helper.awaitStoppedOrGone(serverId)
                        runCatching { api.deleteServer(serverId) }
                    }
                }

                it("disconnecting and reconnecting creates a new session") {
                    val helper = ServerHelper(api)
                    val serverId = helper.createTestServer(nodeId)
                    try {
                        api.startServer(serverId)
                        helper.awaitStatus(serverId, "HEALTHY", 60_000)

                        suspend fun connect(): WebSocket {
                            val ticket = api.authWsTicket()
                            val url = "${wsBaseUrl}/api/ws/console/${serverId}?ticket=${ticket.ticket}"
                            val latch = CountDownLatch(1)
                            val ws = wsClient.newWebSocket(request(url), object : WebSocketListener() {
                                override fun onOpen(webSocket: WebSocket, response: Response) {
                                    latch.countDown()
                                }
                            })
                            latch.await(5, TimeUnit.SECONDS)
                            return ws
                        }

                        val ws1 = connect()
                        ws1.close(1000, "first session")
                        Thread.sleep(1000)

                        val ws2 = connect()
                        ws2.close(1000, "test done")
                    }
                    finally {
                        runCatching { api.stopServer(serverId) }
                        helper.awaitStoppedOrGone(serverId)
                        runCatching { api.deleteServer(serverId) }
                    }
                }
            }
        }
    }

    private val wsBaseUrl: String
        get() = CraftPanelStack.masterApiUrl.replace("http://", "ws://")

    private fun request(url: String): Request = Request.Builder()
        .url(url)
        .build()
}
