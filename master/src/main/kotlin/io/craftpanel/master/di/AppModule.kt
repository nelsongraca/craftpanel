package io.craftpanel.master.di

import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.auth.RefreshTokenService
import io.craftpanel.master.auth.WsTicketService
import io.craftpanel.master.config.AppConfig
import io.craftpanel.master.dns.DnsProvider
import io.craftpanel.master.grpc.BulkDataServiceImpl
import io.craftpanel.master.grpc.ControlServiceImpl
import io.craftpanel.master.grpc.DataServiceProxy
import io.craftpanel.master.scheduler.BackupJobHandler
import io.craftpanel.master.scheduler.ServerScheduler
import io.craftpanel.master.service.AlertService
import io.craftpanel.master.service.AssignmentService
import io.craftpanel.master.service.BackupService
import io.craftpanel.master.service.ContainerLifecycle
import io.craftpanel.master.service.EnvVarsService
import io.craftpanel.master.service.GroupService
import io.craftpanel.master.service.MigrationService
import io.craftpanel.master.service.ModService
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
    // gRPC core
    single { ControlServiceImpl(get<AppConfig>().node) }
    single { BulkDataServiceImpl(get()) }
    single { DataServiceProxy(get(), get()) }

    // Auth
    single { JwtManager(get<AppConfig>().jwt) }
    single { RefreshTokenService() }
    single { WsTicketService() }

    // Domain services
    single { UserService() }
    single { NodeService(get<ControlServiceImpl>()::sendToNode) }
    single { NetworkService() }
    single { GroupService() }
    single { AssignmentService() }
    single { SystemService() }
    single { AlertService() }
    single { ModService() }

    single {
        ContainerLifecycle(
            sendToNode = get<ControlServiceImpl>()::sendToNode,
            agentEvents = get<ControlServiceImpl>().agentEvents,
            modService = get(),
            images = get<AppConfig>().images,
            containerNamePrefix = get(named("containerPrefix")),
        )
    }
    single {
        ServerService(
            sendToNode = get<ControlServiceImpl>()::sendToNode,
            modService = get(),
            dnsProvider = get<DnsProviderHolder>().provider,
            images = get<AppConfig>().images,
            containerNamePrefix = get(named("containerPrefix")),
            lifecycle = get(),
        )
    }
    single { BackupService(get<ControlServiceImpl>()::sendToNode, get()) }
    single { ProxyBackendService() }
    single { EnvVarsService() }

    single {
        MigrationService(
            sendToNode = get<ControlServiceImpl>()::sendToNode,
            agentEvents = get<ControlServiceImpl>().agentEvents,
            dnsProvider = get<DnsProviderHolder>().provider,
            scope = get(named("appScope")),
            lifecycle = get(),
            containerNamePrefix = get(named("containerPrefix")),
        )
    }
    single { BackupJobHandler(get()) }
    single { ServerScheduler(mapOf("BACKUP" to get<BackupJobHandler>()), get(named("appScope"))) }
}
