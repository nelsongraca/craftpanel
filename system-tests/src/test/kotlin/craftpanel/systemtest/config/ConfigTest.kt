package craftpanel.systemtest.config

import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.ServerHelper
import craftpanel.systemtest.client.model.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.openapitools.client.infrastructure.ClientException

class ConfigTest : BaseSystemTest() {

    init {
        context("Server configuration") {

            should("get env vars returns defaults for new server") {
                val serverId = ServerHelper(api).createTestServer(nodeId)
                try {
                    val envVars = api.getEnvVars(serverId)
                    envVars.envVars.shouldNotBeEmpty()
                    envVars.envVars.map { it.key } shouldContain "ALLOW_FLIGHT"
                    envVars.envVars.map { it.key } shouldContain "MOTD"
                    envVars.envVars.map { it.key } shouldContain "MAX_PLAYERS"
                } finally {
                    runCatching { api.deleteServer(serverId) }
                }
            }

            should("sets and retrieves env vars") {
                val serverId = ServerHelper(api).createTestServer(nodeId)
                try {
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
                } finally {
                    runCatching { api.deleteServer(serverId) }
                }
            }

            should("replaces env vars, removing previous entries") {
                val serverId = ServerHelper(api).createTestServer(nodeId)
                try {
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
                } finally {
                    runCatching { api.deleteServer(serverId) }
                }
            }

            should("updates stop command") {
                val serverId = ServerHelper(api).createTestServer(nodeId)
                try {
                    api.updateStopCommand(serverId, PatchStopCommandRequest(stopCommand = "say Goodbye"))
                    val server = api.getServer(serverId)
                    server.stopCommand shouldBe "say Goodbye"
                } finally {
                    runCatching { api.deleteServer(serverId) }
                }
            }

            should("updates config mode") {
                val serverId = ServerHelper(api).createTestServer(nodeId)
                try {
                    api.updateConfigMode(serverId, PatchConfigModeRequest(configMode = "MANAGED"))
                    val server = api.getServer(serverId)
                    server.configMode shouldBe "MANAGED"
                } finally {
                    runCatching { api.deleteServer(serverId) }
                }
            }

            should("managing proxy backends on a non-proxy server returns 409") {
                val serverId = ServerHelper(api).createTestServer(nodeId)
                try {
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
                } finally {
                    runCatching { api.deleteServer(serverId) }
                }
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
                val serverId = ServerHelper(api).createTestServer(nodeId, memoryMb = 512, cpuShares = 128)
                try {
                    api.updateServerResources(
                        serverId,
                        PatchResourcesRequest(memoryMb = 1024, cpuShares = 256)
                    )
                    val server = api.getServer(serverId)
                    server.memoryMb shouldBe 1024
                    server.cpuShares shouldBe 256
                } finally {
                    runCatching { api.deleteServer(serverId) }
                }
            }

            should("updating server resources beyond node capacity returns 409") {
                val node = api.getNode(nodeId)
                val excessiveMb = node.totalRamMb + 1024
                val serverId = ServerHelper(api).createTestServer(nodeId, memoryMb = 512)
                try {
                    val ex = shouldThrow<ClientException> {
                        api.updateServerResources(
                            serverId,
                            PatchResourcesRequest(memoryMb = excessiveMb, cpuShares = 128)
                        )
                    }
                    ex.statusCode shouldBe 409
                } finally {
                    runCatching { api.deleteServer(serverId) }
                }
            }
        }

        context("Server exposure") {

            should("updates server exposure") {
                val serverId = ServerHelper(api).createTestServer(nodeId)
                try {
                    api.updateServerExposure(
                        serverId,
                        PatchExposureRequest(exposedExternally = true)
                    )
                    val server = api.getServer(serverId)
                    server.exposedExternally shouldBe true
                } finally {
                    runCatching { api.deleteServer(serverId) }
                }
            }
        }
    }
}
