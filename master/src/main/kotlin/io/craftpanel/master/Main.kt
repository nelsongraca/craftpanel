package io.craftpanel.master

import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.auth.RefreshTokenService
import io.craftpanel.master.auth.WsTicketService
import io.craftpanel.master.auth.routes.authRoutes
import io.craftpanel.master.config.AppConfig
import io.craftpanel.master.database.DatabaseFactory
import io.craftpanel.master.grpc.ControlServiceImpl
import io.craftpanel.master.grpc.DataServiceProxy
import io.craftpanel.master.grpc.GrpcServer
import io.craftpanel.master.routes.assignmentsRoutes
import io.craftpanel.master.routes.consoleRoutes
import io.craftpanel.master.routes.filesRoutes
import io.craftpanel.master.routes.groupsRoutes
import io.craftpanel.master.routes.networksRoutes
import io.craftpanel.master.routes.nodesRoutes
import io.craftpanel.master.routes.serversRoutes
import io.craftpanel.master.routes.systemRoutes
import io.craftpanel.master.routes.usersRoutes
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.config.AuthScheme
import io.github.smiley4.ktoropenapi.config.AuthType
import io.github.smiley4.ktoropenapi.config.SchemaGenerator
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktorswaggerui.swaggerUI
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.json.Json

fun main(args: Array<String>): Unit = EngineMain.main(args)

fun Application.module() {
    val appConfig = AppConfig(environment.config)

    DatabaseFactory.init(appConfig.database)

    val controlService = ControlServiceImpl(appConfig.node)
    val grpcServer = GrpcServer(appConfig, controlService).start()
    environment.monitor.subscribe(ApplicationStopped) { grpcServer.stop() }

    val jwtManager = JwtManager(appConfig.jwt)
    val refreshTokenService = RefreshTokenService()
    val wsTicketService = WsTicketService()
    val dataServiceProxy = DataServiceProxy(appConfig.node)
    environment.monitor.subscribe(ApplicationStopped) { dataServiceProxy.closeAll() }

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }

    install(WebSockets)

    install(CallLogging)

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
        schemas {
            generator = SchemaGenerator.kotlinx()
        }
    }

    install(Authentication) {
        jwt("auth-jwt") {
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
        authRoutes(jwtManager, refreshTokenService, wsTicketService)
        nodesRoutes(controlService::sendToNode)
        networksRoutes()
        serversRoutes(controlService::sendToNode)
        usersRoutes()
        groupsRoutes()
        assignmentsRoutes()
        systemRoutes()
        consoleRoutes(wsTicketService, dataServiceProxy)
        filesRoutes(dataServiceProxy)
    }
}
