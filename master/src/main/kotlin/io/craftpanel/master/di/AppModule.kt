package io.craftpanel.master.di

import io.craftpanel.master.auth.*
import io.craftpanel.master.config.AppConfig
import io.craftpanel.master.config.ImagesConfig
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
import kotlin.uuid.Uuid

val appModule = module {
    // Repositories
    single<NodeRepository> { NodeRepositoryImpl() }
    single<AlertRepository> { AlertRepositoryImpl() }
    single<EnvVarsRepository> { EnvVarsRepositoryImpl() }
    single<ModRepository> { ModRepositoryImpl() }
    single<MigrationRepository> { MigrationRepositoryImpl() }
    single<PortRepository> { PortRepositoryImpl() }
    single<ServerExtraPortRepository> { ServerExtraPortRepositoryImpl() }
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

    // gRPC core
    single { NodeStateReconciler(nodeRepository = get()) }
    single<AgentGateway> { get<ControlServiceImpl>() }

    // Shared agent events flow
    single { MutableSharedFlow<AgentEvent>(extraBufferCapacity = 1024) }

    // Shared data op context (passed to AgentDataOps and DataOpResponseHandler)
    single { DataOpContext(ConcurrentHashMap(), ConcurrentHashMap()) }

    single { NodeRegistrar(nodeConfig = get<AppConfig>().node, nodeRepository = get()) }
    single {
        AgentDataOps(
            dataOpContext = get(),
            sendToNode = { nodeId, msg -> get<ControlServiceImpl>().sendToNode(nodeId, msg) },
            sendToNodeSuspending = { nodeId, msg -> get<ControlServiceImpl>().sendToNodeSuspending(nodeId, msg) }
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
            nodeRegistrar = get(),
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
    single { DataServiceProxy(get<AgentDataOps>(), get(), get<ServerRepository>()) }
    single { ProxyConfigPatchService(get(), get(), get()) }

    // Observability — subscribes to agentEvents emitted by ControlServiceImpl
    single { AlertEvaluator(alertRepository = get()) }
    single(createdAtStart = true) {
        val csi = get<ControlServiceImpl>()
        NodeObserver(
            agentEvents = csi.agentEvents,
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
    single { TrustedDeviceService(userRepository = get()) }
    single { WsTicketService() }
    single { WsAuthorization(get(), get()) }
    single { TotpService(cipher = get()) }

    // Domain services
    single { UserService(userRepository = get()) }
    single { GroupService(groupRepository = get()) }
    single { AssignmentService(userRepository = get(), groupRepository = get(), serverRepository = get(), networkRepository = get()) }
    single { SystemService(settingsRepository = get()) }
    single { BrandingService(settingsRepository = get()) }
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
            settingsRepository = get(),
            serverRepository = get()
        )
    }

    single {
        val s = get<SystemService>().getSettings().settings
        ImagesConfig(s.imageMinecraft, s.imageProxy)
    }
    single {
        val budgetProvider: () -> Pair<Int, Long> = {
            val s = get<SystemService>().getSettings().settings
            s.restartMaxAttempts to s.restartWindowSeconds
        }
        ContainerLifecycle(
            gateway = get<AgentGateway>(),
            modService = get(),
            serverRepository = get(),
            envVarsRepository = get(),
            extraPortRepository = get(),
            images = get(),
            containerNamePrefix = get(named("containerPrefix")),
            restartBudgetProvider = budgetProvider
        )
    }
    single {
        val dataServiceProxy = get<DataServiceProxy>()
        ServerLifecycleService(
            lifecycle = get(),
            serverRepository = get(),
            serverExposure = get(),
            proxyConfigPatchService = get(),
            writeFile = dataServiceProxy::writeFile
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
            settingsRepository = get(),
            lifecycle = get()
        )
    }
    single {
        ServerProvisioning(
            serverRepository = get(),
            nodeRepository = get(),
            networkRepository = get(),
            settingsRepository = get(),
            portRepository = get(),
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
    single { DesiredStateSyncService(lifecycle = get(), serverRepository = get()) }
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
    single {
        val dataServiceProxy = get<DataServiceProxy>()
        ProxyBackendService(get(), get(), get(), get(), writeFile = dataServiceProxy::writeFile)
    }
    single {
        val dataServiceProxy = get<DataServiceProxy>()
        ProxySettingsService(get(), get(), get(), writeFile = dataServiceProxy::writeFile)
    }
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
