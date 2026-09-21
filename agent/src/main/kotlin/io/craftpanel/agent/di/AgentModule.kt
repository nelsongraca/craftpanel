package io.craftpanel.agent.di

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.desired.ContainerOperator
import io.craftpanel.agent.desired.ConvergenceLoop
import io.craftpanel.agent.desired.DesiredStateStore
import io.craftpanel.agent.docker.*
import io.craftpanel.agent.grpc.AgentOutbound
import io.craftpanel.agent.grpc.BulkDataClient
import io.craftpanel.agent.grpc.CommandDispatcher
import io.craftpanel.agent.grpc.ControlStreamHandler
import io.craftpanel.agent.grpc.MetricsPump
import io.craftpanel.agent.grpc.NodeAuthenticator
import io.craftpanel.agent.grpc.NodeIdentity
import io.craftpanel.agent.grpc.handlers.*
import io.craftpanel.proto.AgentMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose
import java.time.Duration

class ConnectionScope

private fun createDockerClient(socketPath: String): DockerClient {
    val config = DefaultDockerClientConfig.createDefaultConfigBuilder()
        .withDockerHost(socketPath)
        .build()

    val httpClient = ApacheDockerHttpClient.Builder()
        .dockerHost(config.dockerHost)
        .sslConfig(config.sslConfig)
        .maxConnections(100)
        .connectionTimeout(Duration.ofSeconds(30))
        .responseTimeout(Duration.ofSeconds(45))
        .build()

    return DockerClientImpl.getInstance(config, httpClient)
}

val agentModule = module {
    single {
        AgentConfig.fromEnv()
            .also { it.validate() }
    }
    single { createDockerClient(get<AgentConfig>().dockerSocketPath) }
    single { WatcherGate() }
    // Process-scoped: survives agent reconnects. Master re-pushes all envelopes on reconnect and
    // on boot, so no disk persistence is required to re-converge.
    single { DesiredStateStore() }
    single<ContainerManager> {
        DockerContainerManager(
            get<DockerClient>(),
            get<WatcherGate>(),
            get<AgentConfig>().craftpanelNetwork,
            get<AgentConfig>().containerNamePrefix,
            get<AgentConfig>().pullMaxImageAgeHours
        )
    }
    single {
        McRouterProvisioner(
            get(),
            get<AgentConfig>().mcRouterImage,
            get<AgentConfig>().mcRouterUpdateOnStart,
            get<AgentConfig>().craftpanelNetwork,
            get<AgentConfig>().mcRouterContainerName
        )
    }
    single {
        NetworkManager(
            get(),
            get<McRouterProvisioner>().containerName,
            get<AgentConfig>().mcRouterEnabled
        )
    }
    single { RouterSupervisor(get(), get<AgentConfig>().mcRouterEnabled) }
    single {
        MetricsCollector(
            get<DockerClient>(),
            get<AgentConfig>().craftpanelNetwork,
            if (get<AgentConfig>().mcRouterEnabled) get<McRouterProvisioner>().containerName else ""
        )
    }
    single {
        RsyncMigrator(
            get<DockerClient>(),
            get<AgentConfig>().craftpanelNetwork,
            get<AgentConfig>().containerNamePrefix
        )
    }
    single { NodeAuthenticator(get(), get()) }

    scope<ConnectionScope> {
        // Two outbound lanes: console/status/acks must not be starved by telemetry, and telemetry
        // must never back-pressure the metrics collector. The telemetry lane drops its oldest sample
        // when saturated.
        scoped(named(REALTIME_LANE)) { Channel<AgentMessage>(capacity = 256) }
        scoped(named(TELEMETRY_LANE)) { Channel<AgentMessage>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST) }

        scoped { AgentOutbound(get(named(REALTIME_LANE)), get(named(TELEMETRY_LANE)), get<NodeIdentity>().nodeId) }

        // Per-connection convergence scope: cancelled when the connection scope closes, so crash-restart
        // and status-reporting jobs die with the stream. The DesiredStateStore singleton outlives it.
        scoped { CoroutineScope(SupervisorJob()) }.onClose { it?.cancel() }

        scoped {
            ConvergenceLoop(
                store = get(),
                operator = get(),
                containerNamePrefix = get<AgentConfig>().containerNamePrefix,
                gate = get(),
                out = get(),
                scope = get()
            )
        }
        scoped {
            ContainerOperator(
                get<ContainerManager>(),
                get<NetworkManager>(),
                get<AgentConfig>(),
                ensureRouterRunning = { get<RouterSupervisor>().ensureReady() }
            )
        }
        scoped {
            MetricsPump(
                config = get(),
                containerManager = get(),
                metricsCollector = get(),
                routerSupervisor = get(),
                out = get(),
                cpuLimitMillicores = get<ConvergenceLoop>()::cpuLimitMillicores
            )
        }
        scoped { ContainerEventWatcher(get()) }
        scoped { BulkDataClient(get()) }

        scoped { ConsoleHandler(DockerConsoleSession.Factory(get()), DockerLogFetcher(get())) }
        scoped { FileHandler(get(), get<NodeIdentity>().nodeKey) }
        scoped { ContainerHandler(get(), get(), get()) }
        scoped { BackupHandler(get()) }
        scoped { MigrationHandler(get(), get(), get()) }
        scoped { DesiredStateHandler(get()) }

        scoped {
            CommandDispatcher(
                container = get(),
                desired = get(),
                backup = get(),
                migration = get(),
                file = get(),
                console = get(),
                bulkClient = get()
            )
        }
        scoped {
            ControlStreamHandler(
                config = get(),
                containerManager = get(),
                metricsPump = get(),
                routerSupervisor = get(),
                eventWatcher = get(),
                dispatcher = get(),
                gate = get(),
                out = get(),
                loop = get()
            )
        }
    }
}

internal const val REALTIME_LANE = "realtimeLane"
internal const val TELEMETRY_LANE = "telemetryLane"
