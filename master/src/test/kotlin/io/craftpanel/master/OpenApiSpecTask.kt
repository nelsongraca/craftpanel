package io.craftpanel.master

import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.auth.RefreshTokenService
import io.craftpanel.master.auth.routes.authRoutes
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.routes.groupsRoutes
import io.craftpanel.master.routes.networksRoutes
import io.craftpanel.master.routes.nodesRoutes
import io.craftpanel.master.routes.serversRoutes
import io.craftpanel.master.routes.usersRoutes
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.config.AuthScheme
import io.github.smiley4.ktoropenapi.config.AuthType
import io.github.smiley4.ktoropenapi.config.SchemaGenerator
import io.github.smiley4.ktoropenapi.openApi
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
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
                authRoutes(jwtManager, refreshTokenService)
                nodesRoutes { _, _ -> false }
                networksRoutes()
                serversRoutes { _, _ -> false }
                usersRoutes()
                groupsRoutes()
            }
        }

        val spec = client.get("/openapi.json").bodyAsText()
        val output = System.getProperty("openapi.output")
            ?: error("System property 'openapi.output' not set — run via :master:generateOpenApiSpec")
        File(output).writeText(spec)
    }
}
