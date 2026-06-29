package io.craftpanel.master.di

import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.auth.RefreshTokenService
import io.craftpanel.master.auth.WsTicketService
import io.craftpanel.master.config.AppConfig
import io.craftpanel.master.config.ImagesConfig
import io.craftpanel.master.dns.DnsProvider
import io.craftpanel.master.grpc.BulkDataServiceImpl
import io.craftpanel.master.grpc.ControlServiceImpl
import io.craftpanel.master.grpc.DataServiceProxy
import io.craftpanel.master.scheduler.BackupJobHandler
import io.craftpanel.master.scheduler.ServerScheduler
import io.craftpanel.master.service.AgentGateway
import io.craftpanel.master.service.AlertService
import io.craftpanel.master.service.AssignmentService
import io.craftpanel.master.service.BackupService
import io.craftpanel.master.service.ContainerLifecycle
import io.craftpanel.master.service.EnvVarsService
import io.craftpanel.master.service.GroupService
import io.craftpanel.master.service.MigrationService
import io.craftpanel.master.service.ModService
import io.craftpanel.master.service.NetworkService
import io.craftpanel.master.service.NodeObserver
import io.craftpanel.master.service.NodeService
import io.craftpanel.master.service.NodeStateReconciler
import io.craftpanel.master.service.ProxyBackendService
import io.craftpanel.master.service.ServerRestartManager
import io.craftpanel.master.service.ServerExposureService
import io.craftpanel.master.service.ServerLifecycleService
import io.craftpanel.master.service.ServerService
import io.craftpanel.master.service.SystemService
import io.craftpanel.master.service.UserService
import io.craftpanel.master.service.repo.AlertRepository
import io.craftpanel.master.service.repo.AlertRepositoryImpl
import io.craftpanel.master.service.repo.GroupRepository
import io.craftpanel.master.service.repo.GroupRepositoryImpl
import io.craftpanel.master.service.repo.NetworkRepository
import io.craftpanel.master.service.repo.NetworkRepositoryImpl
import io.craftpanel.master.service.repo.NodeRepository
import io.craftpanel.master.service.repo.NodeRepositoryImpl
import io.craftpanel.master.service.repo.ServerRepository
import io.craftpanel.master.service.repo.ServerRepositoryImpl
import io.craftpanel.master.service.repo.SettingsRepository
import io.craftpanel.master.service.repo.SettingsRepositoryImpl
import io.craftpanel.master.service.repo.UserRepository
import io.craftpanel.master.service.repo.UserRepositoryImpl
import io.craftpanel.master.docker.MasterDockerClient
import kotlinx.coroutines.CoroutineScope
import org.koin.core.qualifier.named
import org.koin.dsl.module

val appModule = module {
    // Repositories
    single<NodeRepository> { NodeRepositoryImpl() }
    single<AlertRepository> { AlertRepositoryImpl() }
    single<ServerRepository> { ServerRepositoryImpl() }
    single<NetworkRepository> { NetworkRepositoryImpl() }
    single<GroupRepository> { GroupRepositoryImpl() }
    single<UserRepository> { UserRepositoryImpl() }
    single<SettingsRepository> { SettingsRepositoryImpl() }

    // App-owned crash restart — parameters read from DB settings at startup (takes effect on restart)
    single {
        val s = get<SystemService>().getSettings().settings
        ServerRestartManager(s.restartMaxAttempts, s.restartWindowSeconds)
    }

    // gRPC core
    single { NodeStateReconciler(serverRepository = get(), nodeRepository = get()) }
    single<AgentGateway> { get<ControlServiceImpl>() }
    single {
        ControlServiceImpl(
            nodeConfig = get<AppConfig>().node,
            nodeStateReconciler = get(),
        )
    }
    single { BulkDataServiceImpl(get()) }
    single { DataServiceProxy(get(), get()) }

    // Observability — subscribes to agentEvents emitted by ControlServiceImpl
    single(createdAtStart = true) {
        val csi = get<ControlServiceImpl>()
        NodeObserver(
            agentEvents = csi.agentEvents,
            restartManager = get(),
            // Lazy lookup breaks the NodeObserver <-> ContainerLifecycle construction cycle.
            restartServer = { serverId -> get<ContainerLifecycle>().restartCrashedServer(serverId) },
            emitAgentEvent = { event -> csi.emitToAgentEvents(event) },
            serverRepository = get(),
            nodeRepository = get(),
            alertRepository = get(),
        ).also { it.start(get(named("appScope"))) }
    }

    // Auth
    single { JwtManager(get<AppConfig>().jwt) }
    single { RefreshTokenService() }
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
            groupRepository = get(),
        )
    }
    single { AlertService(alertRepository = get(), nodeRepository = get(), serverRepository = get()) }
    single { ModService(serverRepository = get()) }

    single {
        val s = get<SystemService>().getSettings().settings
        val images = ImagesConfig(s.imageMinecraft, s.imageProxy)
        ContainerLifecycle(
            gateway = get<AgentGateway>(),
            modService = get(),
            serverRepository = get(),
            images = images,
            containerNamePrefix = get(named("containerPrefix")),
        )
    }
    single {
        ServerLifecycleService(
            lifecycle = get(),
            serverRepository = get(),
            networkRepository = get(),
            settingsRepository = get(),
        )
    }
    single {
        ServerExposureService(
            dnsProvider = get<DnsProviderHolder>().provider,
            lifecycle = get(),
            serverRepository = get(),
            nodeRepository = get(),
            networkRepository = get(),
            settingsRepository = get(),
        )
    }
    single {
        val s = get<SystemService>().getSettings().settings
        val images = ImagesConfig(s.imageMinecraft, s.imageProxy)
        ServerService(
            gateway = get<AgentGateway>(),
            modService = get(),
            networkService = get(),
            dnsProvider = get<DnsProviderHolder>().provider,
            images = images,
            containerNamePrefix = get(named("containerPrefix")),
            lifecycle = get(),
            serverRepository = get(),
            nodeRepository = get(),
            networkRepository = get(),
            userRepository = get(),
            groupRepository = get(),
            settingsRepository = get(),
        )
    }
    single { BackupService(get<AgentGateway>(), get(), get()) }
    single { ProxyBackendService(get()) }
    single { EnvVarsService(get()) }

    single {
        MigrationService(
            serverRepository = get<ServerRepository>(),
            nodeRepository = get<NodeRepository>(),
            networkRepository = get<NetworkRepository>(),
            gateway = get<AgentGateway>(),
            dnsProvider = get<DnsProviderHolder>().provider,
            scope = get(named("appScope")),
            lifecycle = get(),
            containerNamePrefix = get(named("containerPrefix")),
        )
    }
    single { BackupJobHandler(get()) }
    single { ServerScheduler(mapOf("BACKUP" to get<BackupJobHandler>()), get(named("appScope"))) }
}
