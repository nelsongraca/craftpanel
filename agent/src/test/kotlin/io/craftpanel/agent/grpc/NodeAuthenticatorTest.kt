package io.craftpanel.agent.grpc

import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.config.RestartBudgetSettings
import io.craftpanel.agent.config.RuntimeSettings
import io.craftpanel.agent.config.RuntimeSettingsStore
import io.craftpanel.agent.docker.MetricsCollector
import io.craftpanel.proto.*
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeTypeOf
import io.mockk.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID

class NodeAuthenticatorTest :
    FunSpec({

        val metrics = mockk<MetricsCollector>()
        val tempDir = Files.createTempDirectory("node-authenticator-test")
        val config = AgentConfig(
            profile = "dev",
            masterAddress = "localhost",
            masterPort = 50051,
            masterHttpPort = 8080,
            tlsCertPath = "",
            caCertFilePath = tempDir.resolve("ca.pem").toString(),
            bootstrapToken = "bootstrap-token-16",
            keyFilePath = tempDir.resolve("node.key").toString(),
            dockerSocketPath = "unix:///var/run/docker.sock",
            dataBasePath = tempDir.toString(),
            hostDataBasePath = tempDir.toString(),
            serversByNameRoot = tempDir.resolve("servers-by-name").toString(),
            backupsByServerRoot = tempDir.resolve("backups-by-server").toString(),
            mcRouterImage = "router:latest",
            mcRouterUpdateOnStart = false,
            mcRouterContainerName = "",
            publicIpUrl = "",
            hostnameOverride = "test-node",
            systemReservedRamMb = -10,
            systemReservedCpuMillicores = -20,
            craftpanelNetwork = "craftpanel",
            containerNamePrefix = "craftpanel",
            privateIpOverride = "192.0.2.10"
        )
        val settingsStore = RuntimeSettingsStore(tempDir.resolve("runtime-settings.json").toFile())

        beforeTest {
            Files.deleteIfExists(tempDir.resolve("node.key"))
            every { metrics.collectCapacity() } returns (-1 to 256)
        }

        afterSpec {
            tempDir.toFile().deleteRecursively()
        }

        test("registers a new node and persists the returned key") {
            val service = object : ControlServiceGrpcKt.ControlServiceCoroutineImplBase() {
                override suspend fun registerNode(request: RegisterNodeRequest): RegisterNodeResponse = registerNodeResponse {
                    nodeId = "node-1"
                    nodeKey = "returned-key"
                }
            }

            val identity = withChannel(service) { channel ->
                runBlocking { NodeAuthenticator(config, metrics, settingsStore).authenticate(channel) }
            }

            identity shouldBe NodeIdentity("node-1", "returned-key")
            Files.readString(tempDir.resolve("node.key")) shouldBe "returned-key"
        }

        test("returns an active identity for an existing key") {
            Files.writeString(tempDir.resolve("node.key"), "existing-key")
            val service = object : ControlServiceGrpcKt.ControlServiceCoroutineImplBase() {
                override suspend fun identifyNode(request: IdentifyNodeRequest): IdentifyNodeResponse = identifyNodeResponse {
                    nodeId = "node-2"
                    status = IdentifyNodeResponse.IdentifyStatus.ACTIVE
                }
            }

            val identity = withChannel(service) { channel ->
                runBlocking { NodeAuthenticator(config, metrics, settingsStore).authenticate(channel) }
            }

            identity shouldBe NodeIdentity("node-2", "existing-key")
        }

        test("returns a pending identity while awaiting approval") {
            Files.writeString(tempDir.resolve("node.key"), "existing-key")
            val service = object : ControlServiceGrpcKt.ControlServiceCoroutineImplBase() {
                override suspend fun identifyNode(request: IdentifyNodeRequest): IdentifyNodeResponse = identifyNodeResponse {
                    nodeId = "node-3"
                    status = IdentifyNodeResponse.IdentifyStatus.PENDING
                }
            }

            val identity = withChannel(service) { channel ->
                runBlocking { NodeAuthenticator(config, metrics, settingsStore).authenticate(channel) }
            }

            identity shouldBe NodeIdentity("node-3", "existing-key")
        }

        test("rejects an unknown or revoked node key") {
            Files.writeString(tempDir.resolve("node.key"), "existing-key")
            val service = object : ControlServiceGrpcKt.ControlServiceCoroutineImplBase() {
                override suspend fun identifyNode(request: IdentifyNodeRequest): IdentifyNodeResponse = identifyNodeResponse {
                    nodeId = "node-4"
                    status = IdentifyNodeResponse.IdentifyStatus.REJECTED
                }
            }

            val result = withChannel(service) { channel ->
                runCatching {
                    runBlocking { NodeAuthenticator(config, metrics, settingsStore).authenticate(channel) }
                }
            }

            result.exceptionOrNull().shouldBeTypeOf<NodeRejectedException>()
            result.exceptionOrNull()?.message shouldBe "Node node-4 was REJECTED by master"
            result.isFailure.shouldBeTrue()
        }

        test("prefers the public IP override over publicIpUrl") {
            val captured = slot<RegisterNodeRequest>()
            val service = object : ControlServiceGrpcKt.ControlServiceCoroutineImplBase() {
                override suspend fun registerNode(request: RegisterNodeRequest): RegisterNodeResponse {
                    captured.captured = request
                    return registerNodeResponse {
                        nodeId = "node-5"
                        nodeKey = "returned-key"
                    }
                }
            }
            val overrideConfig = config.copy(
                publicIpOverride = "203.0.113.5",
                publicIpUrl = "http://127.0.0.1:1/unreachable"
            )

            withChannel(service) { channel ->
                runBlocking { NodeAuthenticator(overrideConfig, metrics, settingsStore).authenticate(channel) }
            }

            captured.captured.metadata.publicIp shouldBe "203.0.113.5"
        }

        test("applies the runtime settings snapshot from a register response") {
            settingsStore.apply(RuntimeSettings(metricsPollIntervalSeconds = 9, metricsCollectionConcurrency = 4))
            val service = object : ControlServiceGrpcKt.ControlServiceCoroutineImplBase() {
                override suspend fun registerNode(request: RegisterNodeRequest): RegisterNodeResponse = registerNodeResponse {
                    nodeId = "node-6"
                    nodeKey = "returned-key"
                    runtimeSettings = agentRuntimeSettings {
                        metricsPollIntervalSeconds = 11
                        metricsCollectionConcurrency = 7
                        reconcileIntervalSeconds = 0
                        restartBudget = restartBudget {
                            maxAttempts = 5
                            windowSeconds = 600
                        }
                        jvmMetricsPollIntervalSeconds = 60
                    }
                }
            }

            withChannel(service) { channel ->
                runBlocking { NodeAuthenticator(config, metrics, settingsStore).authenticate(channel) }
            }

            settingsStore.current() shouldBe RuntimeSettings(
                metricsPollIntervalSeconds = 11,
                metricsCollectionConcurrency = 7,
                reconcileIntervalSeconds = 0,
                restartBudget = RestartBudgetSettings(5, 600),
                jvmMetricsPollIntervalSeconds = 60
            )
        }

        test("applies the runtime settings snapshot from an identify response") {
            Files.writeString(tempDir.resolve("node.key"), "existing-key")
            settingsStore.apply(RuntimeSettings(metricsPollIntervalSeconds = 9, metricsCollectionConcurrency = 4))
            val service = object : ControlServiceGrpcKt.ControlServiceCoroutineImplBase() {
                override suspend fun identifyNode(request: IdentifyNodeRequest): IdentifyNodeResponse = identifyNodeResponse {
                    nodeId = "node-7"
                    status = IdentifyNodeResponse.IdentifyStatus.ACTIVE
                    runtimeSettings = agentRuntimeSettings {
                        metricsPollIntervalSeconds = 2
                        metricsCollectionConcurrency = 3
                        reconcileIntervalSeconds = 20
                        restartBudget = restartBudget {
                            maxAttempts = 4
                            windowSeconds = 300
                        }
                        jvmMetricsPollIntervalSeconds = 15
                    }
                }
            }

            withChannel(service) { channel ->
                runBlocking { NodeAuthenticator(config, metrics, settingsStore).authenticate(channel) }
            }

            settingsStore.current() shouldBe RuntimeSettings(
                metricsPollIntervalSeconds = 2,
                metricsCollectionConcurrency = 3,
                reconcileIntervalSeconds = 20,
                restartBudget = RestartBudgetSettings(4, 300),
                jvmMetricsPollIntervalSeconds = 15
            )
        }

        test("an all-zero snapshot leaves the stored values untouched") {
            settingsStore.apply(RuntimeSettings(metricsPollIntervalSeconds = 9, metricsCollectionConcurrency = 4))
            Files.writeString(tempDir.resolve("node.key"), "existing-key")
            val service = object : ControlServiceGrpcKt.ControlServiceCoroutineImplBase() {
                override suspend fun identifyNode(request: IdentifyNodeRequest): IdentifyNodeResponse = identifyNodeResponse {
                    nodeId = "node-8"
                    status = IdentifyNodeResponse.IdentifyStatus.ACTIVE
                }
            }

            withChannel(service) { channel ->
                runBlocking { NodeAuthenticator(config, metrics, settingsStore).authenticate(channel) }
            }

            settingsStore.current() shouldBe RuntimeSettings(metricsPollIntervalSeconds = 9, metricsCollectionConcurrency = 4)
        }
    }) {
    companion object {
        private fun <T> withChannel(service: ControlServiceGrpcKt.ControlServiceCoroutineImplBase, block: (io.grpc.ManagedChannel) -> T): T {
            val name = "node-auth-${UUID.randomUUID()}"
            val server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(service)
                .build()
                .start()
            val channel = InProcessChannelBuilder.forName(name)
                .directExecutor()
                .build()
            return try {
                block(channel)
            } finally {
                channel.shutdownNow()
                server.shutdownNow()
            }
        }
    }
}
