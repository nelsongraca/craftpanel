package io.craftpanel.master

import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.auth.RefreshTokenService
import io.craftpanel.master.auth.WsTicketService
import io.craftpanel.master.auth.routes.authRoutes
import io.craftpanel.master.config.RateLimitConfig
import io.craftpanel.master.grpc.ControlServiceImpl
import io.craftpanel.master.grpc.DataServiceProxy
import io.craftpanel.master.routes.*
import io.craftpanel.master.service.*
import io.ktor.server.routing.*

data class AppServices(
    val jwtManager: JwtManager,
    val refreshTokenService: RefreshTokenService,
    val wsTicketService: WsTicketService,
    val nodeService: NodeService,
    val networkService: NetworkService,
    val serverService: ServerService,
    val userService: UserService,
    val groupService: GroupService,
    val assignmentService: AssignmentService,
    val systemService: SystemService,
    val backupService: BackupService,
    val proxyBackendService: ProxyBackendService,
    val envVarsService: EnvVarsService,
    val modService: ModService,
    val alertService: AlertService,
    val migrationService: MigrationService,
    val dataServiceProxy: DataServiceProxy,
    val controlService: ControlServiceImpl,
    val rateLimitConfig: RateLimitConfig = RateLimitConfig(10, 30),
    val secureCookies: Boolean = true,
)

fun Route.registerAppRoutes(svc: AppServices) {
    authRoutes(svc.jwtManager, svc.refreshTokenService, svc.wsTicketService, svc.rateLimitConfig, svc.secureCookies)
    nodeIpRoutes()
    nodesRoutes(svc.nodeService)
    networksRoutes(svc.networkService)
    serversRoutes(svc.serverService)
    usersRoutes(svc.userService)
    groupsRoutes(svc.groupService)
    assignmentsRoutes(svc.assignmentService)
    systemRoutes(svc.systemService)
    consoleRoutes(svc.wsTicketService, svc.dataServiceProxy)
    filesRoutes(svc.dataServiceProxy)
    backupsRoutes(svc.backupService)
    configRoutes(svc.proxyBackendService, svc.envVarsService)
    modsRoutes(svc.modService)
    dashboardWsRoutes(svc.wsTicketService, svc.controlService.agentEvents, svc.controlService.agentMetricsFlow)
    alertsRoutes(svc.alertService)
    migrationsRoutes(svc.migrationService)
}
