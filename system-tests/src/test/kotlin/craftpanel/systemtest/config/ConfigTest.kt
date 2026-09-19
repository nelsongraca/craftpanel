package craftpanel.systemtest.config

import craftpanel.systemtest.client.model.*
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException

@Tags("Misc")
class ConfigTest : BaseSystemTest() {

    init {

        lateinit var serverId: String
        lateinit var serverId2: String

        beforeSpec {
            serverId = helper.createTestServer(nodeId)
            api.startServer(serverId)
            helper.awaitStatus(serverId, ServerStatus.HEALTHY)
            serverId2 = helper.createTestServer(nodeId, memoryMb = 512, cpuLimitMillicores = 1000)
        }
        afterSpec {
            runCatching { api.stopServer(serverId) }
            helper.awaitStoppedOrGone(serverId)
            runCatching { api.deleteServer(serverId) }
            runCatching { api.deleteServer(serverId2) }
        }

        context("Server configuration") {

            should("return default env vars for a new server") {
                val envVars = api.getEnvVars(serverId)
                envVars.envVars.shouldNotBeEmpty()
                envVars.envVars.map { it.key } shouldContain "ALLOW_FLIGHT"
                envVars.envVars.map { it.key } shouldContain "MOTD"
                envVars.envVars.map { it.key } shouldContain "MAX_PLAYERS"
            }

            should("set and retrieve env vars") {
                api.replaceEnvVars(
                    serverId,
                    PutEnvVarsRequest(
                        envVars = listOf(
                            EnvVarItem(key = "TEST_KEY", value = "test_value"),
                            EnvVarItem(key = "ANOTHER_KEY", value = "another_value")
                        )
                    )
                )
                val envVars = api.getEnvVars(serverId)
                envVars.envVars.map { it.key } shouldContain "TEST_KEY"
                envVars.envVars.map { it.key } shouldContain "ANOTHER_KEY"
            }

            should("replace env vars and remove previous entries") {
                api.replaceEnvVars(
                    serverId,
                    PutEnvVarsRequest(
                        envVars = listOf(EnvVarItem(key = "FIRST_KEY", value = "first"))
                    )
                )
                api.replaceEnvVars(
                    serverId,
                    PutEnvVarsRequest(
                        envVars = listOf(EnvVarItem(key = "SECOND_KEY", value = "second"))
                    )
                )
                val envVars = api.getEnvVars(serverId)
                envVars.envVars.size shouldBe 1
                envVars.envVars.first().key shouldBe "SECOND_KEY"
            }

            should("update the stop command") {
                api.updateStopCommand(serverId, PatchStopCommandRequest(stopCommand = "say Goodbye"))
                val server = api.getServer(serverId)
                server.stopCommand shouldBe "say Goodbye"
            }

            should("update the config mode") {
                api.updateConfigMode(serverId, PatchConfigModeRequest(configMode = ConfigMode.MANAGED))
                val server = api.getServer(serverId)
                server.configMode shouldBe ConfigMode.MANAGED
            }

            should("return 409 when managing proxy backends on a non-proxy server") {
                val ex = shouldThrow<ClientException> {
                    api.replaceProxyBackends(
                        serverId,
                        PutProxyBackendsRequest(
                            backends = listOf(
                                BackendInput(
                                    backendServerId = "00000000-0000-0000-0000-000000000000",
                                    backendName = "backend1",
                                    order = 1
                                )
                            )
                        )
                    )
                }
                ex.statusCode shouldBe 409
            }

            should("return 404 when configuring a non-existent server") {
                val ex = shouldThrow<ClientException> {
                    api.getEnvVars("00000000-0000-0000-0000-000000000000")
                }
                ex.statusCode shouldBe 404
            }
        }

        context("Server resources") {

            should("update server memory and CPU") {
                api.updateServerResources(
                    serverId2,
                    PatchResourcesRequest(memoryMb = 1024, cpuLimitMillicores = 1000)
                )
                val server = api.getServer(serverId2)
                server.memoryMb shouldBe 1024
                server.cpuLimitMillicores shouldBe 1000
            }

            should("return 409 when updating server resources beyond node capacity") {
                val node = api.getNode(nodeId)
                val excessiveMb = node.totalRamMb + 1024
                val ex = shouldThrow<ClientException> {
                    api.updateServerResources(
                        serverId2,
                        PatchResourcesRequest(memoryMb = excessiveMb, cpuLimitMillicores = 1000)
                    )
                }
                ex.statusCode shouldBe 409
            }
        }

        context("Server exposure") {

            should("update server exposure") {
                api.updateServerExposure(
                    serverId2,
                    PatchExposureRequest(exposedExternally = true)
                )
                val server = api.getServer(serverId2)
                server.exposedExternally shouldBe true
            }
        }
    }
}
