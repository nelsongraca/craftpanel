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
import io.craftpanel.master.service.ServerRestartManager
import io.craftpanel.master.service.EnvVarsService
import io.craftpanel.master.service.GroupService
import io.craftpanel.master.service.MigrationService
import io.craftpanel.master.service.ModService
import io.craftpanel.master.docker.MasterDockerClient
import io.craftpanel.master.service.NetworkService
import io.craftpanel.master.service.NodeService
import io.craftpanel.master.service.ProxyBackendService
import io.craftpanel.master.service.ServerService
import io.craftpanel.master.service.SystemService
import io.craftpanel.master.service.UserService
import kotlinx.coroutines.CoroutineScope
import org.koin.core.qualifier.named
import org.koin.dsl.module

val appModule = module {
    // App-owned crash restart — parameters read from DB settings at startup (takes effect on restart)
    single {
        val s = get<SystemService>().getSettings().settings
        ServerRestartManager(s.restartMaxAttempts, s.restartWindowSeconds)
    }

    // gRPC core
    single<AgentGateway> { get<ControlServiceImpl>() }
    single {
        ControlServiceImpl(
            nodeConfig = get<AppConfig>().node,
            restartManager = get(),
            // Lazy lookup breaks the ControlServiceImpl <-> ContainerLifecycle construction cycle.
            restartServer = { serverId -> get<ContainerLifecycle>().restartCrashedServer(serverId) },
        )
    }
    single { BulkDataServiceImpl(get()) }
    single { DataServiceProxy(get(), get()) }

    // Auth
    single { JwtManager(get<AppConfig>().jwt) }
    single { RefreshTokenService() }
    single { WsTicketService() }

    // Domain services
    single { UserService() }
    single { NodeService(get<AgentGateway>()) }
    single {
        val endpoint = get<AppConfig>().docker.endpoint
        val dockerClient = if (endpoint.isNotEmpty()) MasterDockerClient.create(endpoint) else null
        NetworkService(
            dockerClient = dockerClient,
            containerNamePrefix = get(named("containerPrefix")),
        )
    }
    single { GroupService() }
    single { AssignmentService() }
    single { SystemService() }
    single { AlertService() }
    single { ModService() }

    single {
        val s = get<SystemService>().getSettings().settings
        val images = ImagesConfig(s.imageMinecraft, s.imageProxy)
        ContainerLifecycle(
            gateway = get<AgentGateway>(),
            modService = get(),
            images = images,
            containerNamePrefix = get(named("containerPrefix")),
        )
    }
    single {
        val s = get<SystemService>().getSettings().settings
        val images = ImagesConfig(s.imageMinecraft, s.imageProxy)
        ServerService(
            gateway = get<AgentGateway>(),
            modService = get(),
            dnsProvider = get<DnsProviderHolder>().provider,
            images = images,
            containerNamePrefix = get(named("containerPrefix")),
            lifecycle = get(),
        )
    }
    single { BackupService(get<AgentGateway>(), get()) }
    single { ProxyBackendService() }
    single { EnvVarsService() }

    single {
        MigrationService(
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
