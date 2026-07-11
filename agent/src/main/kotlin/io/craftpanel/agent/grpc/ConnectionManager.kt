package io.craftpanel.agent.grpc

import com.github.dockerjava.api.DockerClient
import io.craftpanel.agent.auth.NodeKeyStore
import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.di.ConnectionScope
import io.craftpanel.agent.docker.*
import io.grpc.ManagedChannel
import io.grpc.netty.GrpcSslContexts
import io.grpc.netty.NettyChannelBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.min
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

class ConnectionManager(
    private val koin: Koin,
    private val config: AgentConfig,
    private val containerManager: ContainerManager,
    private val metricsCollector: MetricsCollector,
    private val docker: DockerClient
) {

    private val log = LoggerFactory.getLogger(ConnectionManager::class.java)

    private fun createChannel(): ManagedChannel {
        val builder = NettyChannelBuilder.forAddress(config.masterAddress, config.masterPort)

        val certPem: String? = when {
            config.tlsEnabled -> File(config.tlsCertPath).readText()
            else -> NodeKeyStore.readCaCert(config.caCertFilePath)
        }

        if (certPem != null) {
            val sslContext = GrpcSslContexts.forClient()
                .trustManager(ByteArrayInputStream(certPem.toByteArray()))
                .build()
            builder.sslContext(sslContext)
        } else {
            check(config.profile == "dev") {
                "gRPC TLS is required outside dev profile — set GRPC_TLS_CERT or mount master's grpc-ca.crt at ${config.caCertFilePath}"
            }
            builder.usePlaintext()
        }

        return builder.build()
    }

    suspend fun run(coroutineScope: CoroutineScope) {
        var backoffSeconds = 5L
        var routerSupervisor: RouterSupervisor? = null
        var networkManager: NetworkManager? = null

        while (true) {
            val result = runCatching {
                log.info("Connecting to master at ${config.masterAddress}:${config.masterPort}")
                val channel = createChannel()

                val scope = koin.createScope(
                    scopeId = "connection-" + System.nanoTime(),
                    qualifier = named<ConnectionScope>()
                )
                try {
                    val identity = NodeAuthenticator(config, metricsCollector).authenticate(channel)
                    backoffSeconds = 5L // reset on successful auth

                    if (routerSupervisor == null) {
                        val provisioner = McRouterProvisioner(
                            docker,
                            config.mcRouterImage,
                            config.mcRouterUpdateOnStart,
                            config.craftpanelNetwork,
                            config.mcRouterContainerName
                        )
                        networkManager = NetworkManager(docker, provisioner.containerName)
                        metricsCollector.mcRouterContainerName = provisioner.containerName
                        val supervisor = RouterSupervisor(provisioner)
                        routerSupervisor = supervisor
                        coroutineScope.launch { supervisor.run() }
                    }

                    ControlStreamHandler(
                        identity, config, containerManager, metricsCollector,
                        routerSupervisor = checkNotNull(routerSupervisor),
                        container = scope.get { parametersOf(checkNotNull(networkManager)) },
                        eventWatcher = ContainerEventWatcher(docker),
                        backup = scope.get(),
                        rsyncMigrator = RsyncMigrator(docker, config.craftpanelNetwork, config.containerNamePrefix),
                        migration = scope.get(),
                        file = scope.get { parametersOf(identity.nodeKey) },
                        console = scope.get()
                    ).run(channel)
                } finally {
                    scope.close()
                    channel.shutdown()
                }
            }

            result.exceptionOrNull()
                ?.let { e ->
                    if (e is NodeRejectedException) {
                        log.error("Node REJECTED by master — halting permanently")
                        exitProcess(1)
                    }
                    log.error("Connection failed: ${e.message} — reconnecting in ${backoffSeconds}s", e)
                }

            delay(backoffSeconds.seconds)
            backoffSeconds = min(backoffSeconds * 2, 120L)
        }
    }
}
