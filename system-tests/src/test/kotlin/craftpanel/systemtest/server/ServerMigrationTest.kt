package craftpanel.systemtest.server

import craftpanel.systemtest.client.api.DefaultApi
import craftpanel.systemtest.client.model.MigrateRequest
import craftpanel.systemtest.client.model.MigrationResponse
import craftpanel.systemtest.harness.AuthHelper
import craftpanel.systemtest.harness.CraftPanelStack
import craftpanel.systemtest.harness.MultiNodeHelper
import craftpanel.systemtest.harness.ServerHelper
import craftpanel.systemtest.harness.pollUntilNotNull
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.openapitools.client.infrastructure.ClientException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ServerMigrationTest : DescribeSpec() {

    private val stack = CraftPanelStack()
    private val api: DefaultApi by lazy { DefaultApi(basePath = stack.masterApiUrl) }
    private val helper: ServerHelper by lazy { ServerHelper(api) }
    private val serverIds = mutableListOf<String>()
    private lateinit var sourceNodeId: String
    private lateinit var targetNodeId: String

    private val wsClient = OkHttpClient.Builder().build()

    init {
        beforeSpec {
            stack.start(nodeCount = 2)
            AuthHelper(api).login()
            val ids = MultiNodeHelper(api).trustAllPendingNodes(2)
            sourceNodeId = ids[0]
            targetNodeId = ids[1]
            stack.storeNodeIds(ids)
        }

        afterSpec {
            serverIds.forEach { id ->
                runCatching { api.stopServer(id) }
                helper.awaitStoppedOrGone(id)
                runCatching { api.deleteServer(id) }
            }
            stack.stop()
        }

        describe("Server migration") {

            it("migrates a STOPPED server to target node and reaches terminal state") {
                val serverId = helper.createTestServer(sourceNodeId).also { serverIds.add(it) }

                val response = api.startMigration(
                    serverId,
                    MigrateRequest(
                        targetNodeId = targetNodeId,
                        rsyncImage = "alpine:latest",
                        playerWarningMessage = "test migration"
                    )
                )

                response.id.isNotEmpty()
                response.status shouldBe "PENDING"

                val migration = pollMigrationStatus(api, response.id, 180_000)
                val isTerminal = migration.status == "COMPLETED" || migration.status == "FAILED"
                if (!isTerminal) throw AssertionError("Expected terminal status but got ${migration.status}")
                if (migration.status == "FAILED") {
                    println("[warn] Migration FAILED: ${migration.steps.lastOrNull()?.errorMessage}")
                }

                val server = api.getServer(serverId)
                server.nodeId shouldBe targetNodeId
            }

            it("server can start on target node after migration") {
                val serverId = helper.createTestServer(sourceNodeId).also { serverIds.add(it) }

                val migrateResp = api.startMigration(
                    serverId,
                    MigrateRequest(
                        targetNodeId = targetNodeId,
                        rsyncImage = "alpine:latest",
                        playerWarningMessage = "test migration"
                    )
                )

                pollMigrationStatus(api, migrateResp.id, 180_000)

                api.startServer(serverId)
                helper.awaitStatus(serverId, "HEALTHY", timeoutMs = 120_000)
            }

            it("receives migration progress events via WebSocket") {
                val serverId = helper.createTestServer(sourceNodeId).also { serverIds.add(it) }

                val migration = api.startMigration(
                    serverId,
                    MigrateRequest(
                        targetNodeId = targetNodeId,
                        rsyncImage = "alpine:latest",
                        playerWarningMessage = "test migration"
                    )
                )

                val wsUrl = stack.masterApiUrl.replace("http://", "ws://")
                val events = mutableListOf<String>()
                val latch = CountDownLatch(1)

                wsClient.newWebSocket(
                    Request.Builder()
                        .url("$wsUrl/api/migrations/${migration.id}/events")
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

            it("listMigrations returns non-empty after migration") {
                val serverId = helper.createTestServer(sourceNodeId).also { serverIds.add(it) }

                val migrateResp = api.startMigration(
                    serverId,
                    MigrateRequest(
                        targetNodeId = targetNodeId,
                        rsyncImage = "alpine:latest",
                        playerWarningMessage = "test migration"
                    )
                )

                pollMigrationStatus(api, migrateResp.id, 180_000)

                val migrations = api.listMigrations(serverId)
                migrations["migrations"].orEmpty().shouldNotBeEmpty()
            }

            it("migrate HEALTHY server is allowed (server is stopped as part of migration)") {
                val serverId = helper.createTestServer(sourceNodeId).also { serverIds.add(it) }
                api.startServer(serverId)
                helper.awaitStatus(serverId, "HEALTHY")

                val response = api.startMigration(
                    serverId,
                    MigrateRequest(
                        targetNodeId = targetNodeId,
                        rsyncImage = "alpine:latest",
                        playerWarningMessage = "test"
                    )
                )
                response.status shouldBe "PENDING"

                val migration = pollMigrationStatus(api, response.id, 180_000)
                migration.status shouldBe "COMPLETED"
            }

            it("migrate to non-existent node returns 404") {
                val serverId = helper.createTestServer(sourceNodeId).also { serverIds.add(it) }

                shouldThrow<ClientException> {
                    api.startMigration(
                        serverId,
                        MigrateRequest(
                            targetNodeId = "00000000-0000-0000-0000-000000000000",
                            rsyncImage = "alpine:latest",
                            playerWarningMessage = "test"
                        )
                    )
                }.statusCode shouldBe 404
            }

            it("get non-existent migration returns 404") {
                shouldThrow<ClientException> {
                    api.getMigration("00000000-0000-0000-0000-000000000000")
                }.statusCode shouldBe 404
            }
        }
    }

    private suspend fun pollMigrationStatus(
        api: DefaultApi,
        migrationId: String,
        timeoutMs: Long,
    ): MigrationResponse {
        return pollUntilNotNull(timeoutMs) {
            api.getMigration(migrationId)
                .takeIf { it.status == "COMPLETED" || it.status == "FAILED" }
        } ?: error("Migration $migrationId did not reach terminal state within ${timeoutMs}ms")
    }
}