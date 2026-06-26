package craftpanel.systemtest.config

import craftpanel.systemtest.client.model.*
import craftpanel.systemtest.client.model.ServerStatus
import craftpanel.systemtest.client.model.ConfigMode
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException

class ConfigTest : BaseSystemTest() {

    init {

        lateinit var serverId: String
        lateinit var serverId2: String

        beforeSpec {
            serverId = helper.createTestServer(nodeId)
            api.startServer(serverId)
            helper.awaitStatus(serverId, ServerStatus.HEALTHY)
            serverId2 = helper.createTestServer(nodeId, memoryMb = 512, cpuShares = 128)
        }
        afterSpec {
            runCatching { api.stopServer(serverId) }
            helper.awaitStoppedOrGone(serverId)
            runCatching { api.deleteServer(serverId) }
            runCatching { api.deleteServer(serverId2) }
        }

        context("Server configuration") {

            should("get env vars returns defaults for new server") {
                val envVars = api.getEnvVars(serverId)
                envVars.envVars.shouldNotBeEmpty()
                envVars.envVars.map { it.key } shouldContain "ALLOW_FLIGHT"
                envVars.envVars.map { it.key } shouldContain "MOTD"
                envVars.envVars.map { it.key } shouldContain "MAX_PLAYERS"
            }

            should("sets and retrieves env vars") {
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

            should("replaces env vars, removing previous entries") {
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

            should("updates stop command") {
                api.updateStopCommand(serverId, PatchStopCommandRequest(stopCommand = "say Goodbye"))
                val server = api.getServer(serverId)
                server.stopCommand shouldBe "say Goodbye"
            }

            should("updates config mode") {
                api.updateConfigMode(serverId, PatchConfigModeRequest(configMode = ConfigMode.MANAGED))
                val server = api.getServer(serverId)
                server.configMode shouldBe ConfigMode.MANAGED
            }

            should("managing proxy backends on a non-proxy server returns 409") {
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

            should("configuring a non-existent server returns 404") {
                val ex = shouldThrow<ClientException> {
                    api.getEnvVars("00000000-0000-0000-0000-000000000000")
                }
                ex.statusCode shouldBe 404
            }
        }

        context("Server resources") {

            should("updates server memory and CPU") {
                api.updateServerResources(
                    serverId2,
                    PatchResourcesRequest(memoryMb = 1024, cpuShares = 256)
                )
                val server = api.getServer(serverId2)
                server.memoryMb shouldBe 1024
                server.cpuShares shouldBe 256
            }

            should("updating server resources beyond node capacity returns 409") {
                val node = api.getNode(nodeId)
                val excessiveMb = node.totalRamMb + 1024
                val ex = shouldThrow<ClientException> {
                    api.updateServerResources(
                        serverId2,
                        PatchResourcesRequest(memoryMb = excessiveMb, cpuShares = 128)
                    )
                }
                ex.statusCode shouldBe 409
            }
        }

        context("Server exposure") {

            should("updates server exposure") {
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
