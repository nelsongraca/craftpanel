package io.craftpanel.master

import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.auth.RefreshTokenService
import io.craftpanel.master.auth.WsTicketService
import io.craftpanel.master.auth.routes.authRoutes
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.config.NodeConfig
import io.craftpanel.master.grpc.DataServiceProxy
import io.craftpanel.master.routes.*
import io.craftpanel.master.service.*
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.config.AuthScheme
import io.github.smiley4.ktoropenapi.config.AuthType
import io.github.smiley4.ktoropenapi.config.SchemaGenerator
import io.github.smiley4.ktoropenapi.openApi
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.ktor.server.websocket.*
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test

class
OpenApiSpecTask {

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
            install(OpenApi) {
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
                schemas { generator = SchemaGenerator.kotlinx() }
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
                val proxy = DataServiceProxy(NodeConfig(bootstrapToken = "test", agentDataPort = 50052))
                val noopSend: (String, com.craftpanel.agent.v1.MasterMessage) -> Boolean = { _, _ -> false }
                val modService = ModService()
                authRoutes(jwtManager, refreshTokenService, wsTicketService)
                nodesRoutes(NodeService(noopSend))
                networksRoutes(NetworkService())
                serversRoutes(ServerService(noopSend, modService))
                usersRoutes(UserService())
                groupsRoutes(GroupService())
                assignmentsRoutes(AssignmentService())
                systemRoutes(SystemService())
                consoleRoutes(wsTicketService, proxy)
                filesRoutes(proxy)
                backupsRoutes(BackupService(noopSend, proxy))
                modsRoutes(modService)
                alertsRoutes(AlertService())
            }
        }

        val spec = client.get("/openapi.json")
            .bodyAsText()
        val output = System.getProperty("openapi.output")
            ?: error("System property 'openapi.output' not set — run via :master:generateOpenApiSpec")
        File(output).writeText(spec)
    }
}
