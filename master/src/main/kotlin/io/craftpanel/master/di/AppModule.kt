package io.craftpanel.master.di

import io.craftpanel.master.auth.*
import io.craftpanel.master.config.AppConfig
import io.craftpanel.master.config.ImagesConfig
import io.craftpanel.master.docker.MasterDockerClient
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.grpc.*
import io.craftpanel.master.grpc.handlers.*
import io.craftpanel.master.scheduler.BackupJobHandler
import io.craftpanel.master.scheduler.ServerScheduler
import io.craftpanel.master.service.*
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

val appModule = module {
    // Repositories
    single<NodeRepository> { NodeRepositoryImpl() }
    single<AlertRepository> { AlertRepositoryImpl() }
    single<EnvVarsRepository> { EnvVarsRepositoryImpl() }
    single<ModRepository> { ModRepositoryImpl() }
    single<MigrationRepository> { MigrationRepositoryImpl() }
    single<PortRepository> { PortRepositoryImpl() }
    single<BackupRepository> { BackupRepositoryImpl() }
    single<ProxyBackendRepository> { ProxyBackendRepositoryImpl() }
    single<ContainerMetricsRepository> { ContainerMetricsRepositoryImpl() }
    single<ServerJobRepository> { ServerJobRepositoryImpl() }
    single<ServerRepository> {
        ServerRepositoryImpl(
            envVarsRepository = get(),
            modRepository = get(),
            migrationRepository = get(),
            portRepository = get(),
            backupRepository = get(),
            proxyBackendRepository = get(),
            containerMetricsRepository = get(),
            serverJobRepository = get()
        )
    }
    single<NetworkRepository> { NetworkRepositoryImpl() }
    single<GroupRepository> { GroupRepositoryImpl() }
    single<UserRepository> { UserRepositoryImpl() }
    single<SettingsRepository> { SettingsRepositoryImpl() }

    // App-owned crash restart — parameters read from DB settings at startup (takes effect on restart)
    single {
        val s = get<SystemService>().getSettings().settings
        ServerRestartManager(s.restartMaxAttempts, s.restartWindowSeconds)
    }

    single(named("crashRestarts")) { Channel<Uuid>(Channel.BUFFERED) }

    // gRPC core
    single { NodeStateReconciler(serverRepository = get(), nodeRepository = get(), migrationRepository = get(), backupRepository = get()) }
    single<AgentGateway> { get<ControlServiceImpl>() }

    // Shared agent events flow
    single { MutableSharedFlow<AgentEvent>(extraBufferCapacity = 1024) }

    // Shared data op context (passed to ControlServiceImpl and DataOpResponseHandler)
    single { DataOpContext(ConcurrentHashMap(), ConcurrentHashMap()) }

    // Handlers
    single { NodeStateHandler(get(), get()) }
    single { NodeMetricsHandler(get(), get()) }
    single { ContainerMetricsHandler(get()) }
    single { ServerStatusHandler(get()) }
    single { PlayerUpdateHandler(get()) }
    single { BackupHandler(get()) }
    single { MigrationHandler(get()) }
    single { DataOpResponseHandler(get()) }

    single {
        ControlServiceImpl(
            nodeConfig = get<AppConfig>().node,
            nodeStateReconciler = get(),
            nodeRepository = get(),
            agentEventsFlow = get(),
            dataOpContext = get(),
            nodeStateHandler = get(),
            nodeMetricsHandler = get(),
            containerMetricsHandler = get(),
            serverStatusHandler = get(),
            playerUpdateHandler = get(),
            backupHandler = get(),
            migrationHandler = get(),
            dataOpResponseHandler = get(),
            serverRepository = get(),
            backupRepository = get()
        )
    }
    single { BulkDataServiceImpl(get()) }
    single { DataServiceProxy(get(), get(), get<ServerRepository>()) }

    // Observability — subscribes to agentEvents emitted by ControlServiceImpl
    single { AlertEvaluator(alertRepository = get()) }
    single(createdAtStart = true) {
        val csi = get<ControlServiceImpl>()
        val lifecycle = get<ContainerLifecycle>()
        lifecycle.startCrashRestartLoop(get(named("appScope")), get<Channel<Uuid>>(named("crashRestarts")))
        NodeObserver(
            agentEvents = csi.agentEvents,
            restartManager = get(),
            crashRestarts = get<Channel<Uuid>>(named("crashRestarts")),
            emitAgentEvent = { event -> csi.emitToAgentEvents(event) },
            serverRepository = get(),
            nodeRepository = get(),
            alertEvaluator = get(),
            containerMetricsRepository = get(),
            backupRepository = get()
        ).also { it.start(get(named("appScope"))) }
    }

    // Auth
    single { PermissionResolver }
    single { JwtManager(get<AppConfig>().jwt) }
    single { RefreshTokenService(userRepository = get()) }
    single { WsTicketService() }

    // Domain services
    single { UserService(userRepository = get()) }
    single { GroupService(groupRepository = get()) }
    single { AssignmentService(userRepository = get(), groupRepository = get(), serverRepository = get(), networkRepository = get()) }
    single { SystemService(settingsRepository = get()) }
    single { NodeService(gateway = get<AgentGateway>(), nodeRepository = get(), serverRepository = get()) }
    single {
        val endpoint = get<AppConfig>().docker.endpoint
        val dockerClient = if (endpoint.isNotEmpty()) MasterDockerClient.create(endpoint) else null
        NetworkService(
            dockerClient = dockerClient,
            containerNamePrefix = get(named("containerPrefix")),
            networkRepository = get(),
            serverRepository = get(),
            nodeRepository = get(),
            userRepository = get(),
            groupRepository = get()
        )
    }
    single { AlertService(alertRepository = get(), nodeRepository = get(), serverRepository = get()) }
    single { ModService(modRepository = get(), serverRepository = get()) }

    single {
        ServerExposure(
            networkRepository = get(),
            settingsRepository = get(),
            serverRepository = get()
        )
    }

    single {
        val s = get<SystemService>().getSettings().settings
        val images = ImagesConfig(s.imageMinecraft, s.imageProxy)
        ContainerLifecycle(
            gateway = get<AgentGateway>(),
            modService = get(),
            serverRepository = get(),
            envVarsRepository = get(),
            images = images,
            containerNamePrefix = get(named("containerPrefix"))
        )
    }
    single {
        ServerLifecycleService(
            lifecycle = get(),
            serverRepository = get(),
            serverExposure = get()
        )
    }
    single {
        ServerExposureService(
            dnsProvider = get<DnsProviderHolder>().provider,
            lifecycle = get(),
            serverRepository = get(),
            nodeRepository = get(),
            serverExposure = get()
        )
    }
    single {
        ServerService(
            gateway = get<AgentGateway>(),
            networkService = get(),
            dnsProvider = get<DnsProviderHolder>().provider,
            containerNamePrefix = get(named("containerPrefix")),
            serverRepository = get(),
            nodeRepository = get(),
            networkRepository = get(),
            userRepository = get(),
            groupRepository = get(),
            settingsRepository = get(),
            serverExposure = get(),
            portRepository = get(),
            envVarsRepository = get(),
            modRepository = get(),
            containerMetricsRepository = get(),
            migrationRepository = get()
        )
    }
    single { BackupService(get<AgentGateway>(), get(), get(), get()) }
    single { ProxyBackendService(get(), get()) }
    single { ProxySettingsService(get()) }
    single { EnvVarsService(get(), get()) }
    single { DashboardService(get(), get(), get(), get(), get()) }

    single {
        MigrationService(
            migrationRepository = get<MigrationRepository>(),
            serverRepository = get<ServerRepository>(),
            portRepository = get<PortRepository>(),
            proxyBackendRepository = get<ProxyBackendRepository>(),
            nodeRepository = get<NodeRepository>(),
            gateway = get<AgentGateway>(),
            dnsProvider = get<DnsProviderHolder>().provider,
            scope = get(named("appScope")),
            lifecycle = get(),
            serverExposure = get(),
            containerNamePrefix = get(named("containerPrefix"))
        )
    }
    single { BackupJobHandler(get()) }
    single { ServerScheduler(mapOf("BACKUP" to get<BackupJobHandler>()), get(named("appScope")), get(), get()) }
}
