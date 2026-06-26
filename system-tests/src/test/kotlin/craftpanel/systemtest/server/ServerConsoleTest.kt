package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.ServerStatus
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.matchers.shouldBe
import okhttp3.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ServerConsoleTest : BaseSystemTest() {

    private val wsClient = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private lateinit var serverId: String

    init {
        beforeSpec {
            serverId = helper.createTestServer(nodeId)
            api.startServer(serverId)
            helper.awaitStatus(serverId, ServerStatus.HEALTHY)
        }

        afterSpec {
            runCatching { api.stopServer(serverId) }
            helper.awaitStoppedOrGone(serverId)
            runCatching { api.deleteServer(serverId) }
        }

        context("Server console WebSocket") {

            context("connection auth") {

                should("rejects connection without a ticket") {
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

                should("connects with valid ticket") {
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

                should("connecting to non-existent server closes normally") {
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

                should("rejects connection with invalid ticket") {
                    val url = "${wsBaseUrl}/api/ws/console/${serverId}?ticket=invalid-fake-ticket"
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
                    closeCode shouldBe 1008
                }
            }

            context("interactive session") {

                should("connects and sends a command") {
                    val ticket = api.authWsTicket()
                    val url = "${wsBaseUrl}/api/ws/console/${serverId}?ticket=${ticket.ticket}"
                    val connectLatch = CountDownLatch(1)

                    val ws = wsClient.newWebSocket(request(url), object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            connectLatch.countDown()
                        }
                    })

                    connectLatch.await(5, TimeUnit.SECONDS)

                    val sent = ws.send("""{"type":"console.input","data":"list\r"}""")
                    sent shouldBe true
                    ws.close(1000, "test done")
                }

                should("disconnecting and reconnecting creates a new session") {
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

                should("sends arbitrary text and appears in Docker logs") {
                    val ticket = api.authWsTicket()
                    val url = "${wsBaseUrl}/api/ws/console/${serverId}?ticket=${ticket.ticket}"
                    val connectLatch = CountDownLatch(1)

                    val ws = wsClient.newWebSocket(request(url), object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            connectLatch.countDown()
                        }
                    })

                    connectLatch.await(5, TimeUnit.SECONDS)
                    ws.send("""{"type":"console.input","data":"hello from test\r"}""")
                    helper.awaitContainerLog(containerName(serverId), "hello from test", docker)
                    ws.close(1000, "test done")
                }

                should("detaches cleanly") {
                    val ticket = api.authWsTicket()
                    val url = "${wsBaseUrl}/api/ws/console/${serverId}?ticket=${ticket.ticket}"
                    val connectLatch = CountDownLatch(1)
                    val closeLatch = CountDownLatch(1)
                    var closeCode = -1

                    val ws = wsClient.newWebSocket(request(url), object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            connectLatch.countDown()
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            closeCode = code; closeLatch.countDown()
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            if (closeCode == -1) closeCode = code; closeLatch.countDown()
                        }
                    })

                    connectLatch.await(5, TimeUnit.SECONDS)
                    ws.close(1000, "bye")
                    closeLatch.await(5, TimeUnit.SECONDS)
                    closeCode shouldBe 1000
                }

                should("sending stop command shuts down the server") {
                    var ws: WebSocket? = null
                    try {
                        val ticket = api.authWsTicket()
                        val url = "${wsBaseUrl}/api/ws/console/${serverId}?ticket=${ticket.ticket}"
                        val connectLatch = CountDownLatch(1)
                        ws = wsClient.newWebSocket(request(url), object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                connectLatch.countDown()
                            }
                        })
                        connectLatch.await(5, TimeUnit.SECONDS)
                        ws.send("""{"type":"console.input","data":"stop\r"}""")
                        helper.awaitStoppedOrGone(serverId)
                        api.getServer(serverId).status shouldBe ServerStatus.STOPPED
                    }
                    finally {
                        ws?.close(1000, "test done")
                    }
                }
            }

            context("stopped server") {

                should("connecting to stopped server closes") {
                    val ticket = api.authWsTicket()
                    val url = "${wsBaseUrl}/api/ws/console/${serverId}?ticket=${ticket.ticket}"
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
                    closeCode shouldBe 1000
                }
            }
        }
    }

    private val wsBaseUrl: String
        get() = masterApiUrl.replace("http://", "ws://")

    private fun request(url: String): Request = Request.Builder()
        .url(url)
        .build()
}
