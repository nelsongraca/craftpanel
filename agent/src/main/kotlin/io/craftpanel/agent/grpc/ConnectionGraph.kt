package io.craftpanel.agent.grpc

import com.github.dockerjava.api.DockerClient
import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.desired.ContainerOperator
import io.craftpanel.agent.desired.ConvergenceLoop
import io.craftpanel.agent.desired.DesiredStateStore
import io.craftpanel.agent.di.ConnectionScope
import io.craftpanel.agent.docker.*
import io.craftpanel.agent.grpc.handlers.DesiredStateHandler
import io.craftpanel.proto.AgentMessage
import io.grpc.ManagedChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope

/**
 * The per-connection object graph: everything built once a stream is authenticated and torn down
 * when it dies. [ConnectionManager] owns the reconnect loop and the once-per-process router/network
 * supervisor; this class owns the rest — the two outbound lanes, the convergence loop, the metrics
 * pump, the dispatcher and the stream handler — and their lifecycle.
 */
class ConnectionGraph private constructor(
    val identity: NodeIdentity,
    val channel: ManagedChannel,
    val realtimeChannel: Channel<AgentMessage>,
    val telemetryChannel: Channel<AgentMessage>,
    val out: AgentOutbound,
    val handler: ControlStreamHandler,
    private val koinScope: Scope,
    private val convergenceScope: CoroutineScope,
) {

    /** Tears down everything this connection built. Safe to call after the stream already failed. */
    fun close() {
        koinScope.close()
        convergenceScope.cancel()
        channel.shutdown()
    }

    companion object {

        fun create(
            koin: Koin,
            config: AgentConfig,
            docker: DockerClient,
            containerManager: ContainerManager,
            metricsCollector: MetricsCollector,
            gate: WatcherGate,
            channel: ManagedChannel,
            identity: NodeIdentity,
            routerSupervisor: RouterSupervisor,
            networkManager: NetworkManager,
        ): ConnectionGraph {
            val koinScope = koin.createScope(
                scopeId = "connection-" + System.nanoTime(),
                qualifier = named<ConnectionScope>()
            )

            // Two outbound lanes: console/status/acks must not be starved by telemetry, and
            // telemetry must never back-pressure the metrics collector. The telemetry lane drops
            // its oldest sample when saturated.
            val realtimeChannel = Channel<AgentMessage>(capacity = 256)
            val telemetryChannel = Channel<AgentMessage>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            val out = AgentOutbound(realtimeChannel, telemetryChannel, identity.nodeId)

            // Per-connection convergence: owns crash-restart + status reporting for the lifetime of
            // this stream. Cancelled when the stream dies; the store (process-scoped singleton)
            // outlives it so reconnect re-pushes converge from saved intent.
            val convergenceScope = CoroutineScope(SupervisorJob())
            val loop = ConvergenceLoop(
                store = koin.get<DesiredStateStore>(),
                operator = ContainerOperator(
                    containerManager,
                    networkManager,
                    config,
                    ensureRouterRunning = { routerSupervisor.ensureReady() }
                ),
                containerNamePrefix = config.containerNamePrefix,
                gate = gate,
                out = out,
                scope = convergenceScope
            )

            val metricsPump = MetricsPump(
                config = config,
                containerManager = containerManager,
                metricsCollector = metricsCollector,
                routerSupervisor = routerSupervisor,
                out = out,
                cpuLimitMillicores = loop::cpuLimitMillicores,
            )

            val handler = ControlStreamHandler(
                config = config,
                containerManager = containerManager,
                metricsPump = metricsPump,
                routerSupervisor = routerSupervisor,
                eventWatcher = ContainerEventWatcher(docker),
                dispatcher = CommandDispatcher(
                    container = koinScope.get { parametersOf(networkManager) },
                    desired = DesiredStateHandler(loop),
                    backup = koinScope.get(),
                    migration = koinScope.get(),
                    file = koinScope.get { parametersOf(identity.nodeKey) },
                    console = koinScope.get(),
                    bulkClient = BulkDataClient(channel)
                ),
                gate = gate,
                out = out,
                loop = loop,
                convergenceScope = convergenceScope
            )

            return ConnectionGraph(
                identity = identity,
                channel = channel,
                realtimeChannel = realtimeChannel,
                telemetryChannel = telemetryChannel,
                out = out,
                handler = handler,
                koinScope = koinScope,
                convergenceScope = convergenceScope,
            )
        }
    }
}
