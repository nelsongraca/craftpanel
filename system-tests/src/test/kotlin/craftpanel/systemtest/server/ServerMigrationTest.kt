package craftpanel.systemtest.server

import craftpanel.systemtest.client.api.DefaultApi
import craftpanel.systemtest.client.model.*
import craftpanel.systemtest.harness.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Isolate
import io.kotest.core.annotation.Tags
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import okhttp3.*
import org.openapitools.client.infrastructure.ClientException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Isolate
@Tags("ServerOps")
class ServerMigrationTest : BaseSystemTest() {

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

        context("Server migration") {

            should("migrates a STOPPED server to target node and reaches terminal state") {
                val serverId = helper.createTestServer(sourceNodeId)
                    .also { serverIds.add(it) }

                val response = api.startMigration(
                    serverId,
                    MigrateRequest(
                        targetNodeId = targetNodeId,
                        rsyncImage = "alpine:latest",
                        playerWarningMessage = "test migration"
                    )
                )

                response.id.isNotEmpty()
                response.status shouldBe MigrationStatus.PENDING

                val migration = pollMigrationStatus(api, response.id, 180_000)
                migration.status shouldBe MigrationStatus.COMPLETED

                val server = api.getServer(serverId)
                server.nodeId shouldBe targetNodeId
            }

            should("server can start on target node after migration") {
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

                pollMigrationStatus(api, migrateResp.id, 180_000)

                api.stopServer(serverId)
                helper.awaitStatus(serverId, ServerStatus.STOPPED, timeoutMs = 60_000)
                api.startServer(serverId)
                helper.awaitStatus(serverId, ServerStatus.HEALTHY, timeoutMs = 120_000)
            }

            should("receives migration progress events via WebSocket") {
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

            should("listMigrations returns non-empty after migration") {
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

                pollMigrationStatus(api, migrateResp.id, 180_000)

                val migrations = api.listMigrations(serverId)
                migrations["migrations"].orEmpty()
                    .shouldNotBeEmpty()
            }

            should("migrate HEALTHY server is allowed (server is stopped as part of migration)") {
                val serverId = helper.createTestServer(sourceNodeId)
                    .also { serverIds.add(it) }
                api.startServer(serverId)
                helper.awaitStatus(serverId, ServerStatus.HEALTHY)

                val response = api.startMigration(
                    serverId,
                    MigrateRequest(
                        targetNodeId = targetNodeId,
                        rsyncImage = "alpine:latest",
                        playerWarningMessage = "test"
                    )
                )
                response.status shouldBe MigrationStatus.PENDING

                val migration = pollMigrationStatus(api, response.id, 180_000)
                migration.status shouldBe MigrationStatus.COMPLETED
            }

            should("migrate to non-existent node returns 404") {
                val serverId = helper.createTestServer(sourceNodeId)
                    .also { serverIds.add(it) }

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

            should("get non-existent migration returns 404") {
                shouldThrow<ClientException> {
                    api.getMigration("00000000-0000-0000-0000-000000000000")
                }.statusCode shouldBe 404
            }
        }
    }

    private suspend fun pollMigrationStatus(api: DefaultApi, migrationId: String, timeoutMs: Long): MigrationResponse = pollUntilNotNull(timeoutMs) {
        api.getMigration(migrationId)
            .takeIf { it.status == MigrationStatus.COMPLETED || it.status == MigrationStatus.FAILED }
    } ?: error("Migration $migrationId did not reach terminal state within ${timeoutMs}ms")
}
