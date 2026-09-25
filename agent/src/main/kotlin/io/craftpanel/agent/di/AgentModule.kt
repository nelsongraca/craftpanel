package io.craftpanel.agent.di

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.config.RuntimeSettingsStore
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
import io.craftpanel.agent.runtime.AgentRuntime
import io.craftpanel.agent.runtime.OutboundSink
import io.craftpanel.proto.AgentMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File
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

internal const val REALTIME_LANE = "realtimeLane"
internal const val TELEMETRY_LANE = "telemetryLane"
internal const val RUNTIME_SCOPE = "runtimeScope"

val agentModule = module {
    // ── Process-scoped ──────────────────────────────────────────────────────
    // These outlive any single control stream: master downtime must not disable crash-restart, and
    // the runtime settings cache must survive reconnects.
    single {
        AgentConfig.fromEnv()
            .also { it.validate() }
    }
    single { createDockerClient(get<AgentConfig>().dockerSocketPath) }
    single { WatcherGate() }
    // In-memory intent store: master re-pushes all envelopes on reconnect and on boot, so no disk
    // persistence is required to re-converge.
    single { DesiredStateStore() }
    single {
        val keyFile = File(get<AgentConfig>().keyFilePath)
        RuntimeSettingsStore(File(keyFile.parentFile ?: File("/app/config"), "runtime-settings.json"))
    }
    single { OutboundSink() }
    single(named(RUNTIME_SCOPE)) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<ContainerManager> {
        DockerContainerManager(
            get<DockerClient>(),
            get<WatcherGate>(),
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
            get<AgentConfig>().mcRouterEnabled,
            get<AgentConfig>().containerNamePrefix
        )
    }
    single { RouterSupervisor(get(), get(), get<AgentConfig>().mcRouterEnabled) }
    single { MetricsCollector(get()) }
    single {
        RsyncMigrator(
            get<DockerClient>(),
            get<AgentConfig>().craftpanelNetwork,
            get<AgentConfig>().containerNamePrefix
        )
    }
    single { NodeAuthenticator(get(), get(), get()) }
    single { ContainerEventWatcher(get()) }
    single {
        ContainerOperator(
            get<ContainerManager>(),
            get<NetworkManager>(),
            get<AgentConfig>(),
            ensureRouterRunning = { get<RouterSupervisor>().ensureReady() }
        )
    }
    single {
        ConvergenceLoop(
            store = get(),
            operator = get(),
            containerNamePrefix = get<AgentConfig>().containerNamePrefix,
            gate = get(),
            out = get(),
            scope = get(named(RUNTIME_SCOPE)),
            runtimeSettings = get()
        )
    }
    single {
        MetricsPump(
            containerManager = get(),
            metricsCollector = get(),
            routerSupervisor = get(),
            out = get(),
            settingsStore = get(),
            cpuLimitMillicores = get<ConvergenceLoop>()::cpuLimitMillicores,
            playerCountProbe = get<ConvergenceLoop>()::playerCountProbe,
            jvmMetricsPolicy = get<ConvergenceLoop>()::jvmMetricsPolicy
        )
    }
    single { RuntimeSettingsHandler(get()) }
    single {
        AgentRuntime(
            scope = get(named(RUNTIME_SCOPE)),
            loop = get(),
            metricsPump = get(),
            eventWatcher = get(),
            gate = get(),
            settingsStore = get()
        )
    }

    // ── Per-connection ──────────────────────────────────────────────────────
    scope<ConnectionScope> {
        // Two outbound lanes: console/status/acks must not be starved by telemetry, and telemetry
        // must never back-pressure the metrics collector. The telemetry lane drops its oldest sample
        // when saturated.
        scoped(named(REALTIME_LANE)) { Channel<AgentMessage>(capacity = 256) }
        scoped(named(TELEMETRY_LANE)) { Channel<AgentMessage>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST) }

        scoped { AgentOutbound(get(named(REALTIME_LANE)), get(named(TELEMETRY_LANE)), get<NodeIdentity>().nodeId) }

        scoped { BulkDataClient(get()) }

        // Console sessions and file ops are inherently connection-scoped; they resolve the
        // process-scoped desired-state objects from the parent scope.
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
                bulkClient = get(),
                runtimeSettings = get()
            )
        }
        scoped {
            ControlStreamHandler(
                containerManager = get(),
                routerSupervisor = get(),
                dispatcher = get(),
                out = get()
            )
        }
    }
}
