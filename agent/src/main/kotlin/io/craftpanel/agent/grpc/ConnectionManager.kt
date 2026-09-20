package io.craftpanel.agent.grpc

import com.github.dockerjava.api.DockerClient
import io.craftpanel.agent.auth.NodeKeyStore
import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.desired.ContainerOperator
import io.craftpanel.agent.desired.ConvergenceLoop
import io.craftpanel.agent.desired.DesiredStateStore
import io.craftpanel.agent.di.ConnectionScope
import io.craftpanel.agent.docker.*
import io.craftpanel.agent.grpc.handlers.DesiredStateHandler
import io.craftpanel.proto.*
import io.grpc.ManagedChannel
import io.grpc.netty.GrpcSslContexts
import io.grpc.netty.NettyChannelBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
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
    private val gate: WatcherGate,
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
                    Heartbeat.beat()

                    if (routerSupervisor == null) {
                        val provisioner = McRouterProvisioner(
                            docker,
                            config.mcRouterImage,
                            config.mcRouterUpdateOnStart,
                            config.craftpanelNetwork,
                            config.mcRouterContainerName
                        )
                        networkManager = NetworkManager(
                            docker,
                            provisioner.containerName,
                            config.mcRouterEnabled
                        )
                        metricsCollector.mcRouterContainerName =
                            if (config.mcRouterEnabled) provisioner.containerName else ""
                        val supervisor = RouterSupervisor(provisioner, config.mcRouterEnabled)
                        routerSupervisor = supervisor
                        coroutineScope.launch { supervisor.run() }
                    }

                    // Two outbound lanes: console/status/acks must not be starved by telemetry,
                    // and telemetry must never back-pressure the metrics collector. The telemetry
                    // lane drops its oldest sample when saturated.
                    val realtimeChannel = Channel<AgentMessage>(capacity = 256)
                    val telemetryChannel = Channel<AgentMessage>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
                    val out = AgentOutbound(realtimeChannel, telemetryChannel, identity.nodeId)
                    // Per-connection convergence: owns the crash-restart + status reporting for the
                    // lifetime of this stream. Cancelled when the stream dies; the store (process-
                    // scoped singleton) outlives it so reconnect re-pushes converge from saved intent.
                    val convergenceScope = CoroutineScope(SupervisorJob())
                    val loop = ConvergenceLoop(
                        store = koin.get<DesiredStateStore>(),
                        operator = ContainerOperator(
                            containerManager,
                            checkNotNull(networkManager),
                            config,
                            ensureRouterRunning = { checkNotNull(routerSupervisor).ensureReady() }
                        ),
                        containerNamePrefix = config.containerNamePrefix,
                        gate = gate,
                        out = out,
                        scope = convergenceScope
                    )

                    ControlStreamHandler(
                        identity = identity,
                        config = config,
                        containerManager = containerManager,
                        metricsCollector = metricsCollector,
                        routerSupervisor = checkNotNull(routerSupervisor),
                        eventWatcher = ContainerEventWatcher(docker),
                        dispatcher = CommandDispatcher(
                            container = scope.get { parametersOf(checkNotNull(networkManager)) },
                            desired = DesiredStateHandler(loop),
                            backup = scope.get(),
                            migration = scope.get(),
                            file = scope.get { parametersOf(identity.nodeKey) },
                            console = scope.get(),
                            bulkClient = BulkDataClient(channel)
                        ),
                        gate = gate,
                        out = out,
                        loop = loop,
                        convergenceScope = convergenceScope
                    ).run(channel, realtimeChannel, telemetryChannel)
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
