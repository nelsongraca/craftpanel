package io.craftpanel.master

import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.auth.RefreshTokenService
import io.craftpanel.master.auth.WsTicketService
import io.craftpanel.master.config.AppConfig
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.config.NodeConfig
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.grpc.BulkDataServiceImpl
import io.craftpanel.master.grpc.ControlServiceImpl
import io.craftpanel.master.grpc.DataOpContext
import io.craftpanel.master.grpc.DataServiceProxy
import io.craftpanel.master.grpc.handlers.*
import io.craftpanel.master.routes.*
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
import io.craftpanel.master.service.NodeStateReconciler
import io.craftpanel.master.service.ProxyBackendService
import io.craftpanel.master.service.ServerExposure
import io.craftpanel.master.service.ServerExposureService
import io.craftpanel.master.service.ServerLifecycleService
import io.craftpanel.master.service.ServerService
import io.craftpanel.master.service.SystemService
import io.craftpanel.master.service.UserService
import io.craftpanel.master.service.repo.AlertRepository
import io.craftpanel.master.service.repo.AlertRepositoryImpl
import io.craftpanel.master.service.repo.GroupRepository
import io.craftpanel.master.service.repo.GroupRepositoryImpl
import io.craftpanel.master.service.repo.NetworkRepository
import io.craftpanel.master.service.repo.NetworkRepositoryImpl
import io.craftpanel.master.service.repo.NodeRepository
import io.craftpanel.master.service.repo.NodeRepositoryImpl
import io.craftpanel.master.service.repo.ServerRepository
import io.craftpanel.master.service.repo.ServerRepositoryImpl
import io.craftpanel.master.service.repo.SettingsRepository
import io.craftpanel.master.service.repo.SettingsRepositoryImpl
import io.craftpanel.master.service.repo.UserRepository
import io.craftpanel.master.service.repo.UserRepositoryImpl
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.config.AuthScheme
import io.github.smiley4.ktoropenapi.config.AuthType
import io.github.smiley4.ktoropenapi.config.SchemaGenerator
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.schemakenerator.swagger.data.RefType
import io.kotest.core.spec.style.FunSpec
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.RateLimitRouteSelector
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.koin.ktor.plugin.KoinIsolated
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class OpenApiSpecTask :
    FunSpec({
        test("generate") {
            val migrationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val jwtConfig = JwtConfig(
                    secret = "spec-generation-secret-must-be-32-plus-characters!!",
                    issuer = "craftpanel",
                    audience = "craftpanel",
                    expirySeconds = 900
                )
                val noopGateway = TestAgentGateway()
                val stubAppConfig = AppConfig(
                    io.ktor.server.config.MapApplicationConfig(
                        "app.profile" to "dev",
                        "database.url" to "jdbc:h2:mem:test",
                        "database.username" to "sa",
                        "database.password" to "",
                        "database.maximumPoolSize" to "5",
                        "jwt.secret" to jwtConfig.secret,
                        "jwt.issuer" to jwtConfig.issuer,
                        "jwt.audience" to jwtConfig.audience,
                        "jwt.expirySeconds" to jwtConfig.expirySeconds.toString(),
                        "grpc.port" to "50051",
                        "node.bootstrapToken" to "test",
                        "node.agentDataPort" to "50052"
                    )
                )

                testApplication {
                    application {
                        install(KoinIsolated) {
                            modules(
                                module {
                                    single { stubAppConfig }
                                    single { jwtConfig }
                                    single { JwtManager(jwtConfig) }
                                    single { RefreshTokenService() }
                                    single { WsTicketService() }
                                    val agentEvents = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 1024)
                                    val nodeRepositoryForControlService = NodeRepositoryImpl()
                                    val nodeStateReconciler = NodeStateReconciler(ServerRepositoryImpl(), nodeRepositoryForControlService)
                                    val dataOpContext = DataOpContext(ConcurrentHashMap(), ConcurrentHashMap())
                                    val nodeStateHandler = NodeStateHandler(agentEvents, nodeStateReconciler)
                                    val nodeMetricsHandler = NodeMetricsHandler(agentEvents, nodeStateReconciler)
                                    val containerMetricsHandler = ContainerMetricsHandler(agentEvents)
                                    val serverStatusHandler = ServerStatusHandler(agentEvents)
                                    val playerUpdateHandler = PlayerUpdateHandler(agentEvents)
                                    val backupHandler = BackupHandler(agentEvents)
                                    val migrationHandler = MigrationHandler(agentEvents)
                                    val dataOpResponseHandler = DataOpResponseHandler(dataOpContext)
                                    single {
                                        ControlServiceImpl(
                                            NodeConfig(bootstrapToken = "test", agentDataPort = 50052),
                                            nodeStateReconciler,
                                            nodeRepositoryForControlService,
                                            agentEventsFlow = agentEvents,
                                            dataOpContext = dataOpContext,
                                            nodeStateHandler = nodeStateHandler,
                                            nodeMetricsHandler = nodeMetricsHandler,
                                            containerMetricsHandler = containerMetricsHandler,
                                            serverStatusHandler = serverStatusHandler,
                                            playerUpdateHandler = playerUpdateHandler,
                                            backupHandler = backupHandler,
                                            migrationHandler = migrationHandler,
                                            dataOpResponseHandler = dataOpResponseHandler
                                        )
                                    }
                                    single { BulkDataServiceImpl(get()) }
                                    single { DataServiceProxy(get(), get(), get<ServerRepository>()) }
                                    single<NodeRepository> { NodeRepositoryImpl() }
                                    single<AlertRepository> { AlertRepositoryImpl() }
                                    single<ServerRepository> { ServerRepositoryImpl() }
                                    single<NetworkRepository> { NetworkRepositoryImpl() }
                                    single<GroupRepository> { GroupRepositoryImpl() }
                                    single<UserRepository> { UserRepositoryImpl() }
                                    single<SettingsRepository> { SettingsRepositoryImpl() }
                                    single { ModService(get()) }
                                    single { NodeService(noopGateway, get(), get()) }
                                    single {
                                        NetworkService(
                                            networkRepository = get(),
                                            serverRepository = get(),
                                            nodeRepository = get(),
                                            userRepository = get(),
                                            groupRepository = get()
                                        )
                                    }
                                    single { UserService(userRepository = get()) }
                                    single { GroupService(groupRepository = get()) }
                                    single { AssignmentService(userRepository = get(), groupRepository = get(), serverRepository = get(), networkRepository = get()) }
                                    single { SystemService(settingsRepository = get()) }
                                    single { AlertService(get(), get(), get()) }
                                    single {
                                        ContainerLifecycle(
                                            gateway = noopGateway,
                                            modService = get(),
                                            serverRepository = get()
                                        )
                                    }
                                    single {
                                        ServerExposure(
                                            networkRepository = get(),
                                            settingsRepository = get(),
                                            serverRepository = get()
                                        )
                                    }
                                    single {
                                        ServerService(
                                            gateway = noopGateway,
                                            serverRepository = get(),
                                            nodeRepository = get(),
                                            networkRepository = get(),
                                            userRepository = get(),
                                            groupRepository = get(),
                                            settingsRepository = get(),
                                            serverExposure = get()
                                        )
                                    }
                                    single {
                                        ServerLifecycleService(
                                            lifecycle = get(),
                                            serverRepository = get(),
                                            serverExposure = get()
                                        )
                                    }
                                    single {
                                        ServerExposureService(
                                            dnsProvider = null,
                                            lifecycle = get(),
                                            serverRepository = get(),
                                            nodeRepository = get(),
                                            serverExposure = get()
                                        )
                                    }
                                    single { BackupService(noopGateway, get(), get()) }
                                    single { ProxyBackendService(get()) }
                                    single { EnvVarsService(get()) }
                                    single {
                                        MigrationService(
                                            serverRepository = get(),
                                            nodeRepository = get(),
                                            gateway = noopGateway,
                                            dnsProvider = null,
                                            scope = migrationScope,
                                            lifecycle = get(),
                                            serverExposure = get()
                                        )
                                    }
                                }
                            )
                        }
                        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                        install(WebSockets)
                        install(RateLimit) {
                            register(RateLimitName("auth-login")) { rateLimiter(limit = 100, refillPeriod = kotlin.time.Duration.INFINITE) }
                            register(RateLimitName("auth-refresh")) { rateLimiter(limit = 100, refillPeriod = kotlin.time.Duration.INFINITE) }
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
                            jwt("auth-jwt") {
                                realm = "CraftPanel"
                                verifier(JwtManager(jwtConfig).verifier)
                                validate { credential ->
                                    if (credential.payload.subject != null) JWTPrincipal(credential.payload) else null
                                }
                                challenge { _, _ ->
                                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                                }
                            }
                        }
                        routing {
                            route("openapi.json") { openApi() }
                            registerAppRoutes()
                        }
                    }

                    val spec = client.get("/openapi.json")
                        .bodyAsText()
                    val output = System.getProperty("openapi.output")
                        ?: error("System property 'openapi.output' not set — run via :master:generateOpenApiSpec")
                    val outputFile = File(output)
                    outputFile.parentFile.mkdirs()
                    outputFile.writeText(spec)
                }
            } finally {
                migrationScope.cancel()
            }
        }
    })
