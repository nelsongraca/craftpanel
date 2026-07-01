package io.craftpanel.master

import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.service.BadGatewayException
import io.craftpanel.master.service.BadRequestException
import io.craftpanel.master.service.ConflictException
import io.craftpanel.master.service.ContainerLifecycleException
import io.craftpanel.master.service.ForbiddenException
import io.craftpanel.master.service.NotFoundException
import io.craftpanel.master.service.UnprocessableException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json

fun ApplicationTestBuilder.testApp(
    extraPlugins: Application.() -> Unit = {},
    routes: Route.(jwtManager: JwtManager) -> Unit,
) {
    application {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(StatusPages) {
            exception<NotFoundException> { call, ex -> call.respond(HttpStatusCode.NotFound, mapOf("error" to (ex.message ?: "Not found"))) }
            exception<ForbiddenException> { call, ex -> call.respond(HttpStatusCode.Forbidden, mapOf("error" to (ex.message ?: "Forbidden"))) }
            exception<ConflictException> { call, ex -> call.respond(HttpStatusCode.Conflict, mapOf("error" to (ex.message ?: "Conflict"))) }
            exception<UnprocessableException> { call, ex -> call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to (ex.message ?: "Unprocessable"))) }
            exception<BadGatewayException> { call, ex -> call.respond(HttpStatusCode.BadGateway, mapOf("error" to (ex.message ?: "Bad gateway"))) }
            exception<BadRequestException> { call, ex -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to (ex.message ?: "Bad request"))) }
            exception<ContainerLifecycleException> { call, ex -> call.respond(HttpStatusCode.BadGateway, mapOf("error" to (ex.message ?: "Lifecycle error"))) }
        }
        extraPlugins()
        val jwtConfig = JwtConfig(
            secret = "test-secret-that-is-at-least-32-characters!!",
            issuer = "craftpanel-test",
            audience = "craftpanel-test",
            expirySeconds = 900,
        )
        val jwtManager = JwtManager(jwtConfig)
        install(Authentication) {
            jwt("auth-jwt") {
                realm = "CraftPanel"
                verifier(jwtManager.verifier)
                validate { credential -> if (credential.payload.subject != null) JWTPrincipal(credential.payload) else null }
                challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token is not valid or has expired")) }
            }
        }
        routing { routes(jwtManager) }
    }
}

fun ApplicationTestBuilder.jsonClient() = createClient {
    install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
}
