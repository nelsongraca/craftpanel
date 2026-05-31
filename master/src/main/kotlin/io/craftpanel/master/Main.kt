package io.craftpanel.master

import io.craftpanel.master.auth.JWT_AUTH
import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.auth.RefreshTokenService
import io.craftpanel.master.auth.WsTicketService
import io.craftpanel.master.auth.routes.authRoutes
import io.craftpanel.master.config.AppConfig
import io.craftpanel.master.database.DatabaseFactory
import io.craftpanel.master.database.migrations.seedAdminUser
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import io.craftpanel.master.grpc.ControlServiceImpl
import io.craftpanel.master.grpc.BulkDataServiceImpl
import io.craftpanel.master.grpc.DataServiceProxy
import io.craftpanel.master.grpc.GrpcServer
import io.craftpanel.master.dns.DnsProviderFactory
import io.craftpanel.master.routes.*
import io.craftpanel.master.service.MigrationService
import io.craftpanel.master.scheduler.BackupJobHandler
import io.craftpanel.master.scheduler.ServerScheduler
import io.craftpanel.master.service.*
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.config.AuthScheme
import io.github.smiley4.ktoropenapi.config.AuthType
import io.github.smiley4.ktoropenapi.config.SchemaGenerator
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktorswaggerui.swaggerUI
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.minutes

private object SecurityHeaderNames {

    const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
    const val X_FRAME_OPTIONS = "X-Frame-Options"
    const val REFERRER_POLICY = "Referrer-Policy"
    const val CONTENT_SECURITY_POLICY = "Content-Security-Policy"
}

fun main(args: Array<String>): Unit = EngineMain.main(args)

fun Application.module() {
    val appConfig = AppConfig(environment.config)
    appConfig.validate()

    DatabaseFactory.init(appConfig.database)
    if (appConfig.adminSeed.enabled) {
        transaction { seedAdminUser(appConfig.adminSeed.email, appConfig.adminSeed.password, appConfig.adminSeed.username) }
    }

    val controlService = ControlServiceImpl(appConfig.node)
    val bulkDataService = BulkDataServiceImpl(controlService)
    val dataServiceProxy = DataServiceProxy(controlService, bulkDataService)
    val grpcServer = GrpcServer(appConfig, controlService, bulkDataService).start()
    monitor.subscribe(ApplicationStopped) { grpcServer.stop() }

    val jwtManager = JwtManager(appConfig.jwt)
    val refreshTokenService = RefreshTokenService()
    val wsTicketService = WsTicketService()

    val dnsProvider = DnsProviderFactory.create(appConfig.dns)
    if (dnsProvider != null) log.info("DNS provider: ${dnsProvider.type}")

    val userService = UserService()
    val nodeService = NodeService(controlService::sendToNode)
    val networkService = NetworkService()
    val groupService = GroupService()
    val assignmentService = AssignmentService()
    val systemService = SystemService()
    val alertService = AlertService()
    val modService = ModService()
    val serverService = ServerService(controlService::sendToNode, modService, dnsProvider)
    val backupService = BackupService(controlService::sendToNode, dataServiceProxy)
    val proxyBackendService = ProxyBackendService()
    val envVarsService = EnvVarsService()
    val migrationService = MigrationService(
        sendToNode = controlService::sendToNode,
        rsyncReadyFlow = controlService.rsyncReadyFlow,
        rsyncProgressFlow = controlService.rsyncProgressFlow,
        rsyncCompleteFlow = controlService.rsyncCompleteFlow,
        serverStatusFlow = controlService.serverStatusFlow,
        dnsProvider = dnsProvider,
        scope = this,
    )

    migrationService.failStuckMigrations()

    val scheduler = ServerScheduler(
        handlers = mapOf("BACKUP" to BackupJobHandler(backupService)),
        scope = this,
    )
    scheduler.start()
    monitor.subscribe(ApplicationStopped) { scheduler.stop() }

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }

    install(WebSockets)

    install(CallLogging) {
        logger = LoggerFactory.getLogger("io.craftpanel.master.http")
        level = Level.DEBUG
        format { call ->
            val status = call.response.status()
            val method = call.request.httpMethod.value
            val path = call.request.path()
            val authHeader = call.request.headers[HttpHeaders.Authorization]?.take(20) ?: "none"
            val failures = call.authentication.allFailures.joinToString { it.toString() }
            val extra = if (status == HttpStatusCode.Unauthorized) " | auth=$authHeader failures=[$failures]" else ""
            "$method $path -> $status$extra"
        }
    }

    install(CORS) {
        allowCredentials = true
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        if (appConfig.cors.allowedHosts.isEmpty()) {
            if (appConfig.profile == "dev") {
                this@module.log.warn("CORS: no allowedHosts configured — allowing all origins (dev mode)")
                anyHost()
            }
        }
        else {
            for (host in appConfig.cors.allowedHosts) {
                allowHost(host, schemes = appConfig.cors.allowedSchemes)
            }
        }
    }

    install(DefaultHeaders) {
        header(SecurityHeaderNames.X_CONTENT_TYPE_OPTIONS, "nosniff")
        header(SecurityHeaderNames.X_FRAME_OPTIONS, "DENY")
        header(SecurityHeaderNames.REFERRER_POLICY, "no-referrer")
        header(
            SecurityHeaderNames.CONTENT_SECURITY_POLICY,
            "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
        )
        if (appConfig.profile != "dev") {
            header(HttpHeaders.StrictTransportSecurity, "max-age=63072000; includeSubDomains")
        }
    }

    install(RateLimit) {
        register(RateLimitName("auth-login")) {
            rateLimiter(limit = appConfig.rateLimit.loginPerMinute, refillPeriod = 1.minutes)
        }
        register(RateLimitName("auth-refresh")) {
            rateLimiter(limit = appConfig.rateLimit.refreshPerMinute, refillPeriod = 1.minutes)
        }
    }

    install(StatusPages) {
        exception<NotFoundException> { call, ex ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse(ex.message ?: "Not found"))
        }
        exception<ForbiddenException> { call, ex ->
            call.respond(HttpStatusCode.Forbidden, ErrorResponse(ex.message ?: "Forbidden"))
        }
        exception<ConflictException> { call, ex ->
            call.respond(HttpStatusCode.Conflict, ErrorResponse(ex.message ?: "Conflict"))
        }
        exception<UnprocessableException> { call, ex ->
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse(ex.message ?: "Unprocessable"))
        }
        exception<BadGatewayException> { call, ex ->
            call.respond(HttpStatusCode.BadGateway, ErrorResponse(ex.message ?: "Bad gateway"))
        }
        exception<BadRequestException> { call, ex ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ex.message ?: "Bad request"))
        }
        exception<PortExhaustedException> { call, ex ->
            call.respond(HttpStatusCode.Conflict, ErrorResponse(ex.message ?: "No free ports available"))
        }
    }

    install(OpenApi) {
        ignoredRouteSelectors += RateLimitRouteSelector::class
        info {
            title = "CraftPanel API"
            version = "1.0.0"
            description = "CraftPanel master REST API"
        }
        server { url = "http://localhost:8080" }
        security {
            securityScheme("BearerAuth") {
                type = AuthType.HTTP
                scheme = AuthScheme.BEARER
            }
            defaultSecuritySchemeNames("BearerAuth")
        }
        schemas {
            generator = SchemaGenerator.kotlinx()
        }
    }

    install(Authentication) {
        jwt(JWT_AUTH) {
            realm = "CraftPanel"
            verifier(jwtManager.verifier)
            validate { credential ->
                if (credential.payload.subject != null) JWTPrincipal(credential.payload) else null
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token is not valid or has expired"))
            }
        }
    }

    routing {
        get("health") { call.respond(mapOf("status" to "ok")) }
        route("openapi.json") { openApi() }
        route("swagger") { swaggerUI("/openapi.json") }
        authRoutes(jwtManager, refreshTokenService, wsTicketService, appConfig.rateLimit)
        nodesRoutes(nodeService)
        networksRoutes(networkService)
        serversRoutes(serverService)
        usersRoutes(userService)
        groupsRoutes(groupService)
        assignmentsRoutes(assignmentService)
        systemRoutes(systemService)
        consoleRoutes(wsTicketService, dataServiceProxy)
        filesRoutes(dataServiceProxy)
        backupsRoutes(backupService)
        configRoutes(proxyBackendService, envVarsService)
        modsRoutes(modService)
        dashboardWsRoutes(wsTicketService, controlService)
        alertsRoutes(alertService)
        migrationsRoutes(migrationService)
    }
}
