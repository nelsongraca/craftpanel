package io.craftpanel.master

import io.craftpanel.master.auth.routes.authRoutes
import io.craftpanel.master.config.AppConfig
import io.craftpanel.master.routes.*
import io.craftpanel.master.service.ServerExposureService
import io.ktor.server.routing.*
import org.koin.ktor.ext.get

fun Route.registerAppRoutes() {
    val cfg = get<AppConfig>()
    authRoutes(get(), get(), get(), get(), cfg.rateLimit, cfg.auth.secureCookies, cfg.auth.cookieDomain)
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
