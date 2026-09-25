package io.craftpanel.agent.di

import com.github.dockerjava.api.DockerClient
import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.grpc.ControlStreamHandler
import io.craftpanel.agent.grpc.NodeIdentity
import io.grpc.ManagedChannel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.dsl.override

/**
 * Resolves the whole per-connection object graph from the real Koin module (DockerClient mocked),
 * so a missing or ambiguous scoped definition fails the build instead of the first live connect.
 */
class AgentModuleTest :
    FunSpec({

        fun testConfig() = AgentConfig(
            profile = "dev",
            masterAddress = "localhost",
            masterPort = 50051,
            masterHttpPort = 8080,
            tlsCertPath = "",
            caCertFilePath = "/tmp/craftpanel-test-ca.crt",
            bootstrapToken = "test-token-16chars",
            keyFilePath = "/tmp/craftpanel-test-node.key",
            dockerSocketPath = "unix:///var/run/docker.sock",
            dataBasePath = "/tmp/craftpanel-test-data",
            hostDataBasePath = "/tmp/craftpanel-test-data",
            serversByNameRoot = "/tmp/craftpanel-test-sbn",
            backupsByServerRoot = "/tmp/craftpanel-test-bbs",
            mcRouterImage = "itzg/mc-router:latest",
            mcRouterUpdateOnStart = false,
            mcRouterContainerName = "",
            publicIpUrl = "",
            hostnameOverride = "",
            systemReservedRamMb = 0,
            systemReservedCpuMillicores = 0,
            craftpanelNetwork = "craftpanel",
            containerNamePrefix = "craftpanel",
            privateIpOverride = ""
        )

        test("per-connection scope resolves the whole control-stream graph") {
            val app = koinApplication {
                modules(
                    agentModule,
                    module {
                        single { testConfig() }.override()
                        single { mockk<DockerClient>(relaxed = true) }.override()
                    }
                )
            }
            try {
                val scope = app.koin.createScope("test-connection", named<ConnectionScope>())
                scope.declare(mockk<ManagedChannel>(relaxed = true))
                scope.declare(NodeIdentity(nodeId = "node-1", nodeKey = "test-key"))

                scope.get<ControlStreamHandler>() shouldNotBe null

                scope.close()
            } finally {
                app.close()
            }
        }
    })
