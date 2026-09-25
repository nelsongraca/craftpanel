package io.craftpanel.master

import io.craftpanel.common.ContainerNames
import io.craftpanel.common.BuildInfo
import io.craftpanel.master.auth.JWT_AUTH
import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.config.AppConfig
import io.craftpanel.master.config.backfillDnsSettingsFromEnv
import io.craftpanel.master.database.DatabaseFactory
import io.craftpanel.master.database.migrations.resetSeedAdminPassword
import io.craftpanel.master.database.migrations.seedAdminUser
import io.craftpanel.master.di.appModule
import io.craftpanel.master.grpc.GrpcServer
import io.craftpanel.master.routes.*
import io.craftpanel.master.scheduler.ServerScheduler
import io.craftpanel.master.service.*
import io.craftpanel.master.service.repo.impl.SettingsRepositoryImpl
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.config.*
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktorswaggerui.swaggerUI
import io.github.smiley4.schemakenerator.swagger.data.RefType
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
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
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
    // TEMPORARY: carry DNS env vars into DB settings for an in-place upgrade. Remove after the
    // deploy wave (delete this call + master/.../config/DnsEnvBackfill.kt).
    backfillDnsSettingsFromEnv(appConfig.forwarding.key)
    if (appConfig.adminSeed.enabled) {
        transaction {
            seedAdminUser(appConfig.adminSeed.email, appConfig.adminSeed.password, appConfig.adminSeed.username)
            if (appConfig.adminSeed.resetPassword) {
                resetSeedAdminPassword(appConfig.adminSeed.email, appConfig.adminSeed.password)
            }
        }
    }

    val startupSettingsRepo = SettingsRepositoryImpl()
    val startupSettings = SystemService(settingsRepository = startupSettingsRepo, settingsProvider = SettingsProvider(startupSettingsRepo)).getSettings().settings

    val appScope: CoroutineScope = this

    install(Koin) {
        slf4jLogger()
        modules(
            module {
                single { appConfig }
                single(named("appScope")) { appScope }
                single(named("containerPrefix")) { System.getenv("CRAFTPANEL_CONTAINER_PREFIX") ?: ContainerNames.DEFAULT_PREFIX }
                single { GrpcServer(get(), get(), get()) }
            },
            appModule
        )
    }

    val jwtManager = get<JwtManager>()
    val grpcServer = get<GrpcServer>().start()
    monitor.subscribe(ApplicationStopped) { grpcServer.stop() }

    get<MigrationService>().failStuckMigrations()
    get<ServerScheduler>().start()
    monitor.subscribe(ApplicationStopped) { get<ServerScheduler>().stop() }

    // Re-push desired-state envelopes for all connected nodes (handles reboot scenarios where
    // agents reconnected while master starts up).
    launch { get<DesiredStateSyncService>().pushAllOnBoot() }

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
        if (appConfig.cors.origins.isEmpty()) {
            if (appConfig.profile == "dev") {
                this@module.log.warn("CORS: no PUBLIC_URLS configured — allowing all origins (dev mode)")
                anyHost()
            }
        }
        else {
            for (origin in appConfig.cors.origins) {
                allowHost(origin.host, schemes = listOf(origin.scheme))
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
            rateLimiter(limit = startupSettings.rateLimitLoginPerMinute, refillPeriod = 1.minutes)
        }
        register(RateLimitName("auth-refresh")) {
            rateLimiter(limit = startupSettings.rateLimitRefreshPerMinute, refillPeriod = 1.minutes)
        }
        register(RateLimitName("auth-totp-verify")) {
            rateLimiter(limit = startupSettings.rateLimitTotpVerifyPerMinute, refillPeriod = 1.minutes)
        }
    }

    install(StatusPages) {
        // Per-request logging stays at DEBUG (CallLogging); errors are logged here so a 5xx is
        // visible at the default INFO level without having to set LOG_LEVEL=DEBUG.
        val httpLog = LoggerFactory.getLogger("io.craftpanel.master.http")

        fun logClientError(call: ApplicationCall, status: HttpStatusCode, ex: Throwable) {
            httpLog.debug(
                "{} {} -> {}: {}",
                call.request.httpMethod.value, call.request.path(), status.value, ex.message
            )
        }

        exception<NotFoundException> { call, ex ->
            logClientError(call, HttpStatusCode.NotFound, ex)
            call.respond(HttpStatusCode.NotFound, ErrorResponse(ex.message ?: "Not found"))
        }
        exception<ForbiddenException> { call, ex ->
            logClientError(call, HttpStatusCode.Forbidden, ex)
            call.respond(HttpStatusCode.Forbidden, ErrorResponse(ex.message ?: "Forbidden"))
        }
        exception<ConflictException> { call, ex ->
            logClientError(call, HttpStatusCode.Conflict, ex)
            call.respond(HttpStatusCode.Conflict, ErrorResponse(ex.message ?: "Conflict"))
        }
        exception<UnprocessableException> { call, ex ->
            logClientError(call, HttpStatusCode.UnprocessableEntity, ex)
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse(ex.message ?: "Unprocessable"))
        }
        exception<BadGatewayException> { call, ex ->
            httpLog.warn(
                "{} {} -> 502: {}",
                call.request.httpMethod.value, call.request.path(), ex.message, ex
            )
            call.respond(HttpStatusCode.BadGateway, ErrorResponse(ex.message ?: "Bad gateway"))
        }
        exception<BadRequestException> { call, ex ->
            logClientError(call, HttpStatusCode.BadRequest, ex)
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ex.message ?: "Bad request"))
        }
        exception<PortExhaustedException> { call, ex ->
            logClientError(call, HttpStatusCode.Conflict, ex)
            call.respond(HttpStatusCode.Conflict, ErrorResponse(ex.message ?: "No free ports available"))
        }
        exception<Throwable> { call, ex ->
            // Coroutine cancellation is control flow, not a server error — let it propagate.
            if (ex is CancellationException) throw ex
            httpLog.error(
                "{} {} -> 500: unhandled {}",
                call.request.httpMethod.value, call.request.path(), ex::class.simpleName, ex
            )
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
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
            generator = SchemaGenerator.kotlinx(json = wsJson) { referencePath = RefType.OPENAPI_SIMPLE }
            schema<ServerMetricsPayload>("ServerMetricsPayload")
            schema<ServerStatusPayload>("ServerStatusPayload")
            schema<ServerPlayersPayload>("ServerPlayersPayload")
            schema<BackupProgressPayload>("BackupProgressPayload")
            schema<BackupCompletePayload>("BackupCompletePayload")
            schema<AlertPayload>("AlertPayload")
            schema<NodeMetricsPayload>("NodeMetricsPayload")
            schema<NodeStatusPayload>("NodeStatusPayload")
            schema<SnapshotPayload>("SnapshotPayload")
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
        get("health") { call.respond(mapOf("status" to "ok", "version" to BuildInfo.version)) }
        route("openapi.json") { openApi() }
        route("swagger") { swaggerUI("/openapi.json") }
        registerAppRoutes()
    }
}
