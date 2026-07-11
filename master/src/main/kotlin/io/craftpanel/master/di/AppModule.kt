package io.craftpanel.master.di

import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.auth.RefreshTokenService
import io.craftpanel.master.auth.WsTicketService
import io.craftpanel.master.config.AppConfig
import io.craftpanel.master.config.ImagesConfig
import io.craftpanel.master.dns.DnsProvider
import io.craftpanel.master.docker.MasterDockerClient
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.grpc.BulkDataServiceImpl
import io.craftpanel.master.grpc.ControlServiceImpl
import io.craftpanel.master.grpc.DataOpContext
import io.craftpanel.master.grpc.DataServiceProxy
import io.craftpanel.master.grpc.handlers.*
import io.craftpanel.master.scheduler.BackupJobHandler
import io.craftpanel.master.scheduler.ServerScheduler
import io.craftpanel.master.service.AgentGateway
import io.craftpanel.master.service.AlertEvaluator
import io.craftpanel.master.service.AlertService
import io.craftpanel.master.service.AssignmentService
import io.craftpanel.master.service.BackupService
import io.craftpanel.master.service.ContainerLifecycle
import io.craftpanel.master.service.DashboardService
import io.craftpanel.master.service.EnvVarsService
import io.craftpanel.master.service.GroupService
import io.craftpanel.master.service.MigrationService
import io.craftpanel.master.service.ModService
import io.craftpanel.master.service.NetworkService
import io.craftpanel.master.service.NodeObserver
import io.craftpanel.master.service.NodeService
import io.craftpanel.master.service.NodeStateReconciler
import io.craftpanel.master.service.ProxyBackendService
import io.craftpanel.master.service.ServerExposure
import io.craftpanel.master.service.ServerExposureService
import io.craftpanel.master.service.ServerLifecycleService
import io.craftpanel.master.service.ServerRestartManager
import io.craftpanel.master.service.ServerService
import io.craftpanel.master.service.SystemService
import io.craftpanel.master.service.UserService
import io.craftpanel.master.service.repo.AlertRepository
import io.craftpanel.master.service.repo.AlertRepositoryImpl
import io.craftpanel.master.service.repo.BackupRepository
import io.craftpanel.master.service.repo.BackupRepositoryImpl
import io.craftpanel.master.service.repo.ContainerMetricsRepository
import io.craftpanel.master.service.repo.ContainerMetricsRepositoryImpl
import io.craftpanel.master.service.repo.EnvVarsRepository
import io.craftpanel.master.service.repo.EnvVarsRepositoryImpl
import io.craftpanel.master.service.repo.GroupRepository
import io.craftpanel.master.service.repo.GroupRepositoryImpl
import io.craftpanel.master.service.repo.MigrationRepository
import io.craftpanel.master.service.repo.MigrationRepositoryImpl
import io.craftpanel.master.service.repo.ModRepository
import io.craftpanel.master.service.repo.ModRepositoryImpl
import io.craftpanel.master.service.repo.NetworkRepository
import io.craftpanel.master.service.repo.NetworkRepositoryImpl
import io.craftpanel.master.service.repo.NodeRepository
import io.craftpanel.master.service.repo.NodeRepositoryImpl
import io.craftpanel.master.service.repo.PortRepository
import io.craftpanel.master.service.repo.PortRepositoryImpl
import io.craftpanel.master.service.repo.ProxyBackendRepository
import io.craftpanel.master.service.repo.ProxyBackendRepositoryImpl
import io.craftpanel.master.service.repo.ServerJobRepository
import io.craftpanel.master.service.repo.ServerJobRepositoryImpl
import io.craftpanel.master.service.repo.ServerRepository
import io.craftpanel.master.service.repo.ServerRepositoryImpl
import io.craftpanel.master.service.repo.SettingsRepository
import io.craftpanel.master.service.repo.SettingsRepositoryImpl
import io.craftpanel.master.service.repo.UserRepository
import io.craftpanel.master.service.repo.UserRepositoryImpl
import io.craftpanel.proto.AgentMessage
import io.craftpanel.proto.ConsoleOutput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
            dataOpResponseHandler = get()
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
            containerMetricsRepository = get(),
            migrationRepository = get()
        )
    }
    single { BackupService(get<AgentGateway>(), get(), get(), get()) }
    single { ProxyBackendService(get(), get()) }
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
