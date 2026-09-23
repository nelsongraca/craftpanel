package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.*
import craftpanel.systemtest.harness.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Isolate
import io.kotest.core.annotation.Tags
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException

/**
 * Migration lifecycle: a server is moved from the source node to the target node and the migration
 * reaches a terminal state. Each test creates and migrates its own server.
 *
 * Split from the former monolithic spec — the six migrations were ~220s of ServerOps' test time, so
 * the post-migration/observability cases live in [ServerMigrationTargetTest] to keep each shard
 * short. Keep both classes `@Isolate`.
 */
@Isolate
@Tags("ServerMigration")
class ServerMigrationTest : BaseSystemTest() {

    private val serverIds = mutableListOf<String>()
    private val sourceNodeId: String = SharedStack.nodeIds[0]
    private val targetNodeId: String = SharedStack.nodeIds[1]

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

            should("migrate a STOPPED server to the target node and reach a terminal state") {
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

                val migration = helper.awaitMigrationTerminal(response.id)
                migration.status shouldBe MigrationStatus.COMPLETED

                val server = api.getServer(serverId)
                server.nodeId shouldBe targetNodeId
            }

            should("migrate a RUNNING server (source guarded) to the target node and reach a terminal state") {
                val serverId = helper.createTestServer(sourceNodeId)
                    .also { serverIds.add(it) }

                api.startServer(serverId)
                helper.awaitStatus(serverId, ServerStatus.HEALTHY, timeoutMs = 120_000)

                val response = api.startMigration(
                    serverId,
                    MigrateRequest(
                        targetNodeId = targetNodeId,
                        rsyncImage = "alpine:latest",
                        playerWarningMessage = "test migration"
                    )
                )

                val migration = helper.awaitMigrationTerminal(response.id)
                migration.status shouldBe MigrationStatus.COMPLETED

                val server = api.getServer(serverId)
                server.nodeId shouldBe targetNodeId
            }

            should("allow migrating a HEALTHY server (it is stopped as part of migration)") {
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

                val migration = helper.awaitMigrationTerminal(response.id)
                migration.status shouldBe MigrationStatus.COMPLETED
            }

            should("return 404 when migrating to a non-existent node") {
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

            should("return 404 when getting a non-existent migration") {
                shouldThrow<ClientException> {
                    api.getMigration("00000000-0000-0000-0000-000000000000")
                }.statusCode shouldBe 404
            }
        }
    }
}
