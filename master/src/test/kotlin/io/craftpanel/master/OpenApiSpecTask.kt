package io.craftpanel.master

import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.auth.RefreshTokenService
import io.craftpanel.master.auth.WsTicketService
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.config.NodeConfig
import io.craftpanel.master.grpc.BulkDataServiceImpl
import io.craftpanel.master.grpc.ControlServiceImpl
import io.craftpanel.master.grpc.DataServiceProxy
import io.craftpanel.master.service.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableSharedFlow
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.config.AuthScheme
import io.github.smiley4.ktoropenapi.config.AuthType
import io.github.smiley4.ktoropenapi.config.SchemaGenerator
import io.github.smiley4.schemakenerator.swagger.data.RefType
import io.github.smiley4.ktoropenapi.openApi
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.ktor.server.websocket.*
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test

@OptIn(DelicateCoroutinesApi::class)
class OpenApiSpecTask {

    @Test
    fun generate() = testApplication {
        val jwtConfig = JwtConfig(
            secret = "spec-generation-secret-must-be-32-plus-characters!!",
            issuer = "craftpanel",
            audience = "craftpanel",
            expirySeconds = 900,
        )
        val jwtManager = JwtManager(jwtConfig)
        val refreshTokenService = RefreshTokenService()

        application {
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
                schemas { generator = SchemaGenerator.kotlinx { referencePath = RefType.OPENAPI_SIMPLE } }
            }
            install(Authentication) {
                jwt("auth-jwt") {
                    realm = "CraftPanel"
                    verifier(jwtManager.verifier)
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
                val wsTicketService = WsTicketService()
                val controlSvc = ControlServiceImpl(NodeConfig(bootstrapToken = "test", agentDataPort = 50052))
                val proxy = DataServiceProxy(controlSvc, BulkDataServiceImpl(controlSvc))
                val noopSend: (String, com.craftpanel.agent.v1.MasterMessage) -> Boolean = { _, _ -> false }
                val modService = ModService()
                registerAppRoutes(AppServices(
                    jwtManager = jwtManager,
                    refreshTokenService = refreshTokenService,
                    wsTicketService = wsTicketService,
                    nodeService = NodeService(noopSend),
                    networkService = NetworkService(),
                    serverService = ServerService(noopSend, modService),
                    userService = UserService(),
                    groupService = GroupService(),
                    assignmentService = AssignmentService(),
                    systemService = SystemService(),
                    backupService = BackupService(noopSend, proxy),
                    proxyBackendService = ProxyBackendService(),
                    envVarsService = EnvVarsService(),
                    modService = modService,
                    alertService = AlertService(),
                    migrationService = MigrationService(
                        sendToNode = { _, _ -> false },
                        rsyncReadyFlow = MutableSharedFlow(),
                        rsyncProgressFlow = MutableSharedFlow(),
                        rsyncCompleteFlow = MutableSharedFlow(),
                        serverStatusFlow = MutableSharedFlow(),
                        dnsProvider = null,
                        scope = GlobalScope,
                    ),
                    dataServiceProxy = proxy,
                    controlService = controlSvc,
                ))
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
}
