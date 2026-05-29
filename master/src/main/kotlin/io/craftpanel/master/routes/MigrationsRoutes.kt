package io.craftpanel.master.routes

import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.service.MigrateRequest
import io.craftpanel.master.service.MigrationResponse
import io.craftpanel.master.service.MigrationService
import io.craftpanel.master.util.toKotlinUuid
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.launch
import java.util.*

fun Route.migrationsRoutes(migrationService: MigrationService) {
    authenticate("auth-jwt") {
        route("/api/servers/{id}/migrations") {

            get("", {
                operationId = "listMigrations"
                summary = "List server migrations"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.OK) { body<Map<String, List<MigrationResponse>>>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseMigrationServerId(call.parameters["id"])
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val scope = migrationService.getServerScope(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                val serverIdJava = UUID.fromString(id.toString())
                if (!PermissionResolver.hasPermission(userId, "server.migrate", serverId = serverIdJava, networkId = scope.networkId))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                call.respond(mapOf("migrations" to migrationService.listMigrations(id)))
            }

            post("", {
                operationId = "startMigration"
                summary = "Start server migration to another node"
                request { pathParameter<String>("id"); body<MigrateRequest>() }
                response {
                    code(HttpStatusCode.Accepted) { body<MigrationResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseMigrationServerId(call.parameters["id"])
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val scope = migrationService.getServerScope(id)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                val serverIdJava = UUID.fromString(id.toString())
                if (!PermissionResolver.hasPermission(userId, "server.migrate", serverId = serverIdJava, networkId = scope.networkId))
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<MigrateRequest>()
                call.respond(HttpStatusCode.Accepted, migrationService.startMigration(id, req))
            }
        }

        route("/api/migrations/{migrationId}") {

            get("", {
                operationId = "getMigration"
                summary = "Get migration status"
                request { pathParameter<String>("migrationId") }
                response {
                    code(HttpStatusCode.OK) { body<MigrationResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                call.userId()
                val migrationId = call.parameters["migrationId"]
                    ?.let { runCatching { UUID.fromString(it).toKotlinUuid() }.getOrNull() }
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid migration ID"))
                call.respond(migrationService.getMigration(migrationId))
            }
        }
    }

    webSocket("/api/migrations/{migrationId}/events") {
        val migrationIdStr = call.parameters["migrationId"] ?: run {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing migration ID"))
            return@webSocket
        }
        val flow = migrationService.getEventFlow(migrationIdStr) ?: run {
            close(CloseReason(CloseReason.Codes.NORMAL, "Migration not found or already completed"))
            return@webSocket
        }

        val job = launch {
            flow.collect { event ->
                outgoing.trySend(Frame.Text(event.toString()))
                val type = event["type"]?.toString()?.trim('"')
                if (type == "completed" || type == "failed") {
                    close(CloseReason(CloseReason.Codes.NORMAL, "Migration finished"))
                }
            }
        }
        try {
            for (_frame in incoming) { /* server-push only */ }
        } finally {
            job.cancel()
        }
    }
}

private fun parseMigrationServerId(raw: String?): kotlin.uuid.Uuid? =
    raw?.let {
        runCatching { UUID.fromString(it).toKotlinUuid() }.getOrNull()
    }
