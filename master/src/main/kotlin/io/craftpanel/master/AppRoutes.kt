package io.craftpanel.master

import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.auth.RefreshTokenService
import io.craftpanel.master.auth.WsTicketService
import io.craftpanel.master.auth.routes.authRoutes
import io.craftpanel.master.config.AppConfig
import io.craftpanel.master.grpc.DataServiceProxy
import io.craftpanel.master.routes.alertsRoutes
import io.craftpanel.master.routes.assignmentsRoutes
import io.craftpanel.master.routes.backupsRoutes
import io.craftpanel.master.routes.configRoutes
import io.craftpanel.master.routes.consoleRoutes
import io.craftpanel.master.routes.dashboardWsRoutes
import io.craftpanel.master.routes.filesRoutes
import io.craftpanel.master.routes.groupsRoutes
import io.craftpanel.master.routes.migrationsRoutes
import io.craftpanel.master.routes.modsRoutes
import io.craftpanel.master.routes.networksRoutes
import io.craftpanel.master.routes.nodeIpRoutes
import io.craftpanel.master.routes.nodesRoutes
import io.craftpanel.master.routes.serversRoutes
import io.craftpanel.master.routes.systemRoutes
import io.craftpanel.master.routes.usersRoutes
import io.craftpanel.master.service.AlertService
import io.craftpanel.master.service.AssignmentService
import io.craftpanel.master.service.BackupService
import io.craftpanel.master.service.EnvVarsService
import io.craftpanel.master.service.GroupService
import io.craftpanel.master.service.MigrationService
import io.craftpanel.master.service.ModService
import io.craftpanel.master.service.NetworkService
import io.craftpanel.master.service.NodeService
import io.craftpanel.master.service.ProxyBackendService
import io.craftpanel.master.service.ServerExposureService
import io.craftpanel.master.service.ServerLifecycleService
import io.craftpanel.master.service.ServerService
import io.craftpanel.master.service.SystemService
import io.craftpanel.master.service.UserService
import io.ktor.server.routing.Route
import org.koin.ktor.ext.get

fun Route.registerAppRoutes() {
    val cfg = get<AppConfig>()
    authRoutes(get(), get(), get(), get(), cfg.rateLimit, cfg.auth.secureCookies)
    nodeIpRoutes()
    nodesRoutes(get())
    networksRoutes(get())
    serversRoutes(get(), get(), get<ServerExposureService>())
    usersRoutes(get())
    groupsRoutes(get())
    assignmentsRoutes(get())
    systemRoutes(get())
    consoleRoutes(get(), get(), get())
    filesRoutes(get())
    backupsRoutes(get())
    configRoutes(get(), get())
    modsRoutes(get())
    dashboardWsRoutes(get(), get())
    alertsRoutes(get())
    migrationsRoutes(get())
}
