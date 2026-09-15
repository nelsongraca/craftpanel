package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.CreateNetworkRequest
import craftpanel.systemtest.client.model.EnvVarItem
import craftpanel.systemtest.client.model.PutEnvVarsRequest
import craftpanel.systemtest.client.model.ServerStatus
import craftpanel.systemtest.client.model.UpdateServerRequest
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.delay
import org.openapitools.client.infrastructure.ClientException
import kotlin.time.Duration.Companion.milliseconds

@Tags("ServerCore")
class ServerUpdateTest : BaseSystemTest() {

    init {

        lateinit var serverId: String

        beforeSpec {
            serverId = helper.createTestServer(nodeId)
        }
        afterSpec {
            runCatching { api.deleteServer(serverId) }
        }

        context("Server update") {

            should("updates server name") {
                api.updateServer(serverId, UpdateServerRequest(displayName = "renamed-server"))
                val server = api.getServer(serverId)
                server.displayName shouldBe "renamed-server"
            }

            should("updates server description") {
                api.updateServer(serverId, UpdateServerRequest(description = "test description"))
                val server = api.getServer(serverId)
                server.description shouldBe "test description"
            }

            should("updates server network") {
                val network = api.createNetwork(
                    CreateNetworkRequest(name = "update-net-${System.currentTimeMillis()}")
                )
                try {
                    api.updateServer(serverId, UpdateServerRequest(networkId = network.id))
                    val server = api.getServer(serverId)
                    server.networkId shouldBe network.id
                }
                finally {
                    runCatching { api.deleteNetwork(network.id) }
                }
            }

            should("updating non-existent server returns 404") {
                shouldThrow<ClientException> {
                    api.updateServer(
                        "00000000-0000-0000-0000-000000000000",
                        UpdateServerRequest(displayName = "ghost")
                    )
                }.statusCode shouldBe 404
            }

            should("partial update only changes specified fields") {
                val original = api.getServer(serverId)
                val originalName = original.name

                api.updateServer(serverId, UpdateServerRequest(description = "only description"))
                val updated = api.getServer(serverId)
                updated.description shouldBe "only description"
                updated.name shouldBe originalName
            }

            should("update is idempotent") {
                api.updateServer(serverId, UpdateServerRequest(displayName = "idempotent-name"))
                api.updateServer(serverId, UpdateServerRequest(displayName = "idempotent-name"))
                val server = api.getServer(serverId)
                server.displayName shouldBe "idempotent-name"
            }
        }

        context("Reconfigure (recreate-if-diff)") {

            should("an env change while running does not restart, but a restart recreates with the new spec") {
                api.startServer(serverId)
                helper.awaitStatus(serverId, ServerStatus.HEALTHY)
                val before = docker.inspectContainerCmd(containerName(serverId)).exec().id

                api.replaceEnvVars(
                    serverId,
                    PutEnvVarsRequest(envVars = listOf(EnvVarItem(key = "RECONFIG_MARKER", value = "changed")))
                )

                // Reconfigure while running must NOT restart or recreate: same container, still HEALTHY.
                api.getServer(serverId).status shouldBe ServerStatus.HEALTHY
                docker.inspectContainerCmd(containerName(serverId)).exec().id shouldBe before

                // A restart detects the spec diff and recreates the container (new container id).
                api.restartServer(serverId)
                var after = before
                val deadline = System.currentTimeMillis() + 60_000
                while (System.currentTimeMillis() < deadline && after == before) {
                    delay(250.milliseconds)
                    after = runCatching {
                        docker.inspectContainerCmd(containerName(serverId)).exec().id
                    }.getOrNull() ?: before
                }
                after shouldNotBe before
                helper.awaitStatus(serverId, ServerStatus.HEALTHY)
                docker.inspectContainerCmd(containerName(serverId)).exec()
                    .config?.env?.toList().orEmpty()
                    .any { it.startsWith("RECONFIG_MARKER=") } shouldBe true

                api.stopServer(serverId)
                helper.awaitStoppedOrGone(serverId)
            }
        }
    }
}