package io.craftpanel.agent.grpc

import io.craftpanel.agent.config.AgentConfig
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

class NodeAuthenticatorTest : FunSpec({

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
        agentVersion = "test",
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
        privateIpOverride = "192.0.2.10",
        metricsPollIntervalSeconds = 5
    )

    beforeTest {
        Files.deleteIfExists(tempDir.resolve("node.key"))
        every { metrics.collectCapacity() } returns (-1 to 256)
    }

    afterSpec {
        tempDir.toFile().deleteRecursively()
    }

    test("registers a new node and persists the returned key") {
        val service = object : ControlServiceGrpcKt.ControlServiceCoroutineImplBase() {
            override suspend fun registerNode(request: RegisterNodeRequest): RegisterNodeResponse =
                registerNodeResponse {
                    nodeId = "node-1"
                    nodeKey = "returned-key"
                }
        }

        val identity = withChannel(service) { channel ->
            runBlocking { NodeAuthenticator(config, metrics).authenticate(channel) }
        }

        identity shouldBe NodeIdentity("node-1", "returned-key")
        Files.readString(tempDir.resolve("node.key")) shouldBe "returned-key"
    }

    test("returns an active identity for an existing key") {
        Files.writeString(tempDir.resolve("node.key"), "existing-key")
        val service = object : ControlServiceGrpcKt.ControlServiceCoroutineImplBase() {
            override suspend fun identifyNode(request: IdentifyNodeRequest): IdentifyNodeResponse =
                identifyNodeResponse {
                    nodeId = "node-2"
                    status = IdentifyNodeResponse.IdentifyStatus.ACTIVE
                }
        }

        val identity = withChannel(service) { channel ->
            runBlocking { NodeAuthenticator(config, metrics).authenticate(channel) }
        }

        identity shouldBe NodeIdentity("node-2", "existing-key")
    }

    test("returns a pending identity while awaiting approval") {
        Files.writeString(tempDir.resolve("node.key"), "existing-key")
        val service = object : ControlServiceGrpcKt.ControlServiceCoroutineImplBase() {
            override suspend fun identifyNode(request: IdentifyNodeRequest): IdentifyNodeResponse =
                identifyNodeResponse {
                    nodeId = "node-3"
                    status = IdentifyNodeResponse.IdentifyStatus.PENDING
                }
        }

        val identity = withChannel(service) { channel ->
            runBlocking { NodeAuthenticator(config, metrics).authenticate(channel) }
        }

        identity shouldBe NodeIdentity("node-3", "existing-key")
    }

    test("rejects an unknown or revoked node key") {
        Files.writeString(tempDir.resolve("node.key"), "existing-key")
        val service = object : ControlServiceGrpcKt.ControlServiceCoroutineImplBase() {
            override suspend fun identifyNode(request: IdentifyNodeRequest): IdentifyNodeResponse =
                identifyNodeResponse {
                    nodeId = "node-4"
                    status = IdentifyNodeResponse.IdentifyStatus.REJECTED
                }
        }

        val result = withChannel(service) { channel ->
            runCatching {
                runBlocking { NodeAuthenticator(config, metrics).authenticate(channel) }
            }
        }

        result.exceptionOrNull().shouldBeTypeOf<NodeRejectedException>()
        result.exceptionOrNull()?.message shouldBe "Node node-4 was REJECTED by master"
        result.isFailure.shouldBeTrue()
    }
}) {
    companion object {
        private fun <T> withChannel(
            service: ControlServiceGrpcKt.ControlServiceCoroutineImplBase,
            block: (io.grpc.ManagedChannel) -> T
        ): T {
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
