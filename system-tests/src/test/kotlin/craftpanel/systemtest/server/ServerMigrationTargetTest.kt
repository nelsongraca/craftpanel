package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.*
import craftpanel.systemtest.harness.*
import io.kotest.core.annotation.Isolate
import io.kotest.core.annotation.Tags
import io.kotest.matchers.collections.shouldNotBeEmpty
import okhttp3.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Post-migration behaviour: after a server has been moved to the target node it starts there, the
 * migration is observable over WebSocket, and it is listed. Split out of the former monolithic
 * `ServerMigrationTest` so the six migrations are not one shard's critical path.
 *
 * Keep `@Isolate`, matching [ServerMigrationTest].
 */
@Isolate
@Tags("ServerMigrationTarget")
class ServerMigrationTargetTest : BaseSystemTest() {

    private val serverIds = mutableListOf<String>()
    private val sourceNodeId: String = SharedStack.nodeIds[0]
    private val targetNodeId: String = SharedStack.nodeIds[1]

    private val wsClient = OkHttpClient.Builder()
        .build()

    init {
        afterEach {
            serverIds.forEach { id ->
                runCatching { api.stopServer(id) }
                runCatching { helper.awaitStoppedOrGone(id) }
                runCatching { api.deleteServer(id) }
            }
            serverIds.clear()
        }

        afterSpec {
            serverIds.forEach { id ->
                runCatching { api.deleteServer(id) }
            }
        }

        context("Server migration follow-up") {

            should("start a server on the target node after migration") {
                val serverId = helper.createTestServer(sourceNodeId)
                    .also { serverIds.add(it) }

                val migrateResp = api.startMigration(
                    serverId,
                    MigrateRequest(
                        targetNodeId = targetNodeId,
                        rsyncImage = "alpine:latest",
                        playerWarningMessage = "test migration"
                    )
                )

                helper.awaitMigrationTerminal(migrateResp.id)

                api.stopServer(serverId)
                helper.awaitStatus(serverId, ServerStatus.STOPPED, timeoutMs = 60_000)
                api.startServer(serverId)
                helper.awaitStatus(serverId, ServerStatus.HEALTHY, timeoutMs = 120_000)
            }

            should("receive migration progress events via WebSocket") {
                val serverId = helper.createTestServer(sourceNodeId)
                    .also { serverIds.add(it) }

                val migration = api.startMigration(
                    serverId,
                    MigrateRequest(
                        targetNodeId = targetNodeId,
                        rsyncImage = "alpine:latest",
                        playerWarningMessage = "test migration"
                    )
                )

                val wsUrl = masterApiUrl.replace("http://", "ws://")
                val ticket = api.authWsTicket()
                val events = mutableListOf<String>()
                val latch = CountDownLatch(1)

                wsClient.newWebSocket(
                    Request.Builder()
                        .url("$wsUrl/api/migrations/${migration.id}/events?ticket=${ticket.ticket}")
                        .build(),
                    object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            events.add(text)
                            if (text.contains("\"completed\"") || text.contains("\"failed\"")) {
                                latch.countDown()
                            }
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            latch.countDown()
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            latch.countDown()
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            latch.countDown()
                        }
                    }
                )

                latch.await(30, TimeUnit.SECONDS)
                events.shouldNotBeEmpty()
            }

            should("return a non-empty migration list after migration") {
                val serverId = helper.createTestServer(sourceNodeId)
                    .also { serverIds.add(it) }

                val migrateResp = api.startMigration(
                    serverId,
                    MigrateRequest(
                        targetNodeId = targetNodeId,
                        rsyncImage = "alpine:latest",
                        playerWarningMessage = "test migration"
                    )
                )

                helper.awaitMigrationTerminal(migrateResp.id)

                val migrations = api.listMigrations(serverId)
                migrations["migrations"].orEmpty()
                    .shouldNotBeEmpty()
            }
        }
    }
}
