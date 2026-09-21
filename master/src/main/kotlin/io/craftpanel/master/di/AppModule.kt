package io.craftpanel.master.di

import io.craftpanel.master.auth.*
import io.craftpanel.master.config.AppConfig
import io.craftpanel.master.crypto.SecretCipher
import io.craftpanel.master.docker.MasterDockerClient
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.grpc.*
import io.craftpanel.master.grpc.handlers.*
import io.craftpanel.master.scheduler.BackupJobHandler
import io.craftpanel.master.scheduler.ServerScheduler
import io.craftpanel.master.service.*
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
import kotlinx.coroutines.flow.MutableSharedFlow
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.util.concurrent.ConcurrentHashMap

val appModule = module {
    // Repositories
    single<NodeRepository> { NodeRepositoryImpl() }
    single<AlertRepository> { AlertRepositoryImpl() }
    single<EnvVarsRepository> { EnvVarsRepositoryImpl() }
    single<ModRepository> { ModRepositoryImpl() }
    single<MigrationRepository> { MigrationRepositoryImpl() }
    single<PortRepository> { PortRepositoryImpl() }
    single { PortAllocator(nodeRepository = get(), portRepository = get()) }
    single<ServerExtraPortRepository> { ServerExtraPortRepositoryImpl(get()) }
    single<BackupRepository> { BackupRepositoryImpl() }
    single<ProxyBackendRepository> { ProxyBackendRepositoryImpl() }
    single<ContainerMetricsRepository> { ContainerMetricsRepositoryImpl() }
    single<ServerJobRepository> { ServerJobRepositoryImpl() }
    single<ServerRepository> {
        ServerRepositoryImpl()
    }
    single<NetworkRepository> { NetworkRepositoryImpl() }
    single<GroupRepository> { GroupRepositoryImpl() }
    single<UserRepository> { UserRepositoryImpl() }
    single<RecoveryCodeRepository> { RecoveryCodeRepositoryImpl() }
    single<SettingsRepository> { SettingsRepositoryImpl() }
    single { SettingsProvider(get()) }

    // gRPC core
    single { NodeStateReconciler(nodeRepository = get()) }

    // Shared agent events flow
    single { MutableSharedFlow<AgentEvent>(extraBufferCapacity = 1024) }

    // Agent connection registry + outbound gateway
    single { AgentRegistry(get(), get(), get()) }
    single<AgentGateway> { get<AgentRegistry>() }

    // Shared data op context (passed to AgentDataOps and DataOpResponseHandler)
    single { DataOpContext(ConcurrentHashMap(), ConcurrentHashMap()) }

    single { NodeRegistrationService(nodeConfig = get<AppConfig>().node, nodeRepository = get()) }
    single {
        val registry = get<AgentRegistry>()
        AgentDataOps(
            dataOpContext = get(),
            sendToNode = { nodeId, msg -> registry.sendToNode(nodeId, msg) },
            sendToNodeSuspending = { nodeId, msg -> registry.sendToNodeSuspending(nodeId, msg) }
        )
    }

    // Handlers
    single {
        NodeStateHandler(
            agentEvents = get(),
            nodeStateReconciler = get(),
            // Lazy lookup: resolving DesiredStateSyncService here would cycle through
            // ContainerLifecycle → AgentGateway → ControlServiceImpl → this handler.
            pushDesiredStates = { nodeId -> get<DesiredStateSyncService>().pushAllForNode(nodeId) }
        )
    }
    single { NodeMetricsHandler(get(), get()) }
    single { ContainerMetricsHandler(get()) }
    single { ServerStatusHandler(get()) }
    single { PlayerUpdateHandler(get()) }
    single { BackupHandler(get()) }
    single { MigrationHandler(get()) }
    single { DataOpResponseHandler(get()) }

    single {
        ControlServiceImpl(
            nodeStateReconciler = get(),
            nodeRegistrationService = get(),
            registry = get(),
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
    single { DataServiceProxy(get<AgentDataOps>(), get(), get<ServerRepository>()) }
    single {
        val settingsProvider = get<SettingsProvider>()
        ProxyConfigPatchService(get(), get()) { settingsProvider.images() }
    }

    // Observability — subscribes to agentEvents emitted by AgentRegistry
    single { AlertEvaluator(alertRepository = get()) }
    single(createdAtStart = true) {
        val registry = get<AgentRegistry>()
        NodeObserver(
            agentEvents = registry.agentEvents,
            emitAgentEvent = { event -> registry.emit(event) },
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
    single { TrustedDeviceService(userRepository = get()) }
    single { WsTicketService() }
    single { WsAuthorization(get(), get()) }
    single { TotpService(cipher = get()) }

    // Domain services
    single { UserService(userRepository = get()) }
    single { GroupService(groupRepository = get()) }
    single { AssignmentService(userRepository = get(), groupRepository = get(), serverRepository = get(), networkRepository = get()) }
    single { SystemService(settingsRepository = get(), settingsProvider = get()) }
    single { BrandingService(settingsProvider = get()) }
    single { NodeService(gateway = get<AgentGateway>(), nodeRepository = get(), serverRepository = get(), nodeRegistrationService = get()) }
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
        ServerHostnames(
            settingsProvider = get(),
            serverRepository = get()
        )
    }

    single { ServerIntent(serverRepository = get()) }
    single {
        val dataServiceProxy = get<DataServiceProxy>()
        ProxyPatchWriter(patchService = get(), writeFile = dataServiceProxy::writeFile)
    }
    single {
        val settingsProvider = get<SettingsProvider>()
        val budgetProvider: () -> Pair<Int, Long> = {
            val s = settingsProvider.current()
            s.restartMaxAttempts to s.restartWindowSeconds
        }
        ContainerLifecycle(
            gateway = get<AgentGateway>(),
            modService = get(),
            serverIntent = get(),
            envVarsRepository = get(),
            extraPortRepository = get(),
            imagesProvider = { settingsProvider.images() },
            containerNamePrefix = get(named("containerPrefix")),
            restartBudgetProvider = budgetProvider
        )
    }
    single {
        ServerLifecycleService(
            lifecycle = get(),
            serverRepository = get(),
            serverHostnames = get(),
            serverIntent = get(),
            proxyPatchWriter = get()
        )
    }
    single {
        ServerExposureService(
            dnsProvider = get<DnsProviderHolder>().provider,
            lifecycle = get(),
            serverRepository = get(),
            nodeRepository = get(),
            serverHostnames = get()
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
            settingsProvider = get(),
            lifecycle = get()
        )
    }
    single {
        ServerProvisioning(
            serverRepository = get(),
            nodeRepository = get(),
            networkRepository = get(),
            settingsProvider = get(),
            portAllocator = get(),
            extraPortRepository = get(),
            envVarsRepository = get(),
            modRepository = get(),
            networkService = get(),
            containerNamePrefix = get(named("containerPrefix"))
        )
    }
    single {
        ServerQueryService(
            serverRepository = get(),
            userRepository = get(),
            groupRepository = get(),
            containerMetricsRepository = get(),
            migrationRepository = get()
        )
    }
    single { BackupService(get<AgentGateway>(), get(), get(), get(), get(named("containerPrefix"))) }
    single { DesiredStateSyncService(lifecycle = get(), serverRepository = get(), serverIntent = get()) }
    single {
        SecretCipher(
            java.util.Base64.getDecoder()
                .decode(get<AppConfig>().forwarding.key)
        )
    }
    single {
        val dataServiceProxy = get<DataServiceProxy>()
        BackendForwardingService(
            serverRepository = get(),
            proxyBackendRepository = get(),
            envVarsRepository = get(),
            cipher = get(),
            writeFile = dataServiceProxy::writeFile
        )
    }
    single { ProxyBackendService(get(), get(), get(), get()) }
    single { ProxySettingsService(get(), get(), get()) }
    single { EnvVarsService(get(), get()) }
    single { DashboardService(get(), get(), get(), get(), get()) }
    single {
        ExportService(
            serverRepository = get(),
            networkRepository = get(),
            envVarsRepository = get(),
            modRepository = get(),
            extraPortRepository = get(),
            proxyBackendRepository = get(),
            provisioning = get(),
            networkService = get()
        )
    }

    single {
        MigrationService(
            migrationRepository = get<MigrationRepository>(),
            serverRepository = get<ServerRepository>(),
            portAllocator = get(),
            proxyBackendRepository = get<ProxyBackendRepository>(),
            nodeRepository = get<NodeRepository>(),
            gateway = get<AgentGateway>(),
            dnsProvider = get<DnsProviderHolder>().provider,
            scope = get(named("appScope")),
            lifecycle = get(),
            serverHostnames = get(),
            containerNamePrefix = get(named("containerPrefix"))
        )
    }
    single { BackupJobHandler(get()) }
    single {
        ServerScheduler(
            mapOf("BACKUP" to get<BackupJobHandler>()),
            get(named("appScope")),
            get(),
            get(),
            get<ServerLifecycleService>()
        )
    }
}
