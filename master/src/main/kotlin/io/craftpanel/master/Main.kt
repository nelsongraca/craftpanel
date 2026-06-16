package io.craftpanel.master

import io.craftpanel.master.auth.JWT_AUTH
import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.config.AppConfig
import io.craftpanel.master.database.DatabaseFactory
import io.craftpanel.master.database.migrations.seedAdminUser
import io.craftpanel.master.di.DnsProviderHolder
import io.craftpanel.master.di.appModule
import io.craftpanel.master.dns.DnsProvider
import io.craftpanel.master.dns.DnsProviderFactory
import io.craftpanel.master.grpc.GrpcServer
import io.craftpanel.master.routes.ErrorResponse
import io.craftpanel.master.scheduler.ServerScheduler
import io.craftpanel.master.service.BadGatewayException
import io.craftpanel.master.service.BadRequestException
import io.craftpanel.master.service.ConflictException
import io.craftpanel.master.service.ForbiddenException
import io.craftpanel.master.service.MigrationService
import io.craftpanel.master.service.NotFoundException
import io.craftpanel.master.service.PortExhaustedException
import io.craftpanel.master.service.UnprocessableException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.config.AuthScheme
import io.github.smiley4.ktoropenapi.config.AuthType
import io.github.smiley4.ktoropenapi.config.SchemaGenerator
import io.github.smiley4.schemakenerator.swagger.data.RefType
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktorswaggerui.swaggerUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
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
    if (appConfig.adminSeed.enabled) {
        transaction { seedAdminUser(appConfig.adminSeed.email, appConfig.adminSeed.password, appConfig.adminSeed.username) }
    }

    val appScope: CoroutineScope = this
    val dnsProvider: DnsProvider? = DnsProviderFactory.create(appConfig.dns)
    if (dnsProvider != null) log.info("DNS provider: ${dnsProvider.type}")

    install(Koin) {
        slf4jLogger()
        modules(
            module {
                single { appConfig }
                single(named("appScope")) { appScope }
                single(named("containerPrefix")) { System.getenv("CRAFTPANEL_CONTAINER_PREFIX") ?: "craftpanel" }
                single { DnsProviderHolder(dnsProvider) }
                single { GrpcServer(get(), get(), get()) }
            },
            appModule,
        )
    }

    val jwtManager = get<JwtManager>()
    val grpcServer = get<GrpcServer>().start()
    monitor.subscribe(ApplicationStopped) { grpcServer.stop() }

    get<MigrationService>().failStuckMigrations()
    get<ServerScheduler>().start()
    monitor.subscribe(ApplicationStopped) { get<ServerScheduler>().stop() }

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
            generator = SchemaGenerator.kotlinx { referencePath = RefType.OPENAPI_SIMPLE }
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
        registerAppRoutes()
    }
}
