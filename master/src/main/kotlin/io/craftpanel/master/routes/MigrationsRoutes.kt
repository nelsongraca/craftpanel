@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.craftpanel.master.routes

import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.JWT_AUTH
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.service.MigrateRequest
import io.craftpanel.master.service.MigrationEvent
import io.craftpanel.master.service.MigrationResponse
import io.craftpanel.master.service.MigrationService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import io.craftpanel.master.util.toKotlinUuid
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.github.tabilzad.ktor.annotations.KtorDescription
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import java.util.*

private val migrationJson = Json { classDiscriminator = "type"; namingStrategy = JsonNamingStrategy.SnakeCase }

fun Route.migrationsRoutes(migrationService: MigrationService) {
    authenticate(JWT_AUTH) {
        route("/api/servers/{id}/migrations") {

            @KtorDescription(operationId = "listMigrations", summary = "List server migrations")
            get("") {
                val userId = call.userId()
                val id = parseMigrationServerId(call.parameters["id"])
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val scope = migrationService.getServerScope(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                val serverIdJava = UUID.fromString(id.toString())
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_MIGRATE, serverId = serverIdJava, networkId = scope.networkId))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                call.respond(mapOf("migrations" to migrationService.listMigrations(id)))
            }

            @KtorDescription(operationId = "startMigration", summary = "Start server migration to another node")
            post("") {
                val userId = call.userId()
                val id = parseMigrationServerId(call.parameters["id"])
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val scope = migrationService.getServerScope(id)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                val serverIdJava = UUID.fromString(id.toString())
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_MIGRATE, serverId = serverIdJava, networkId = scope.networkId))
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<MigrateRequest>()
                call.respond(HttpStatusCode.Accepted, migrationService.startMigration(id, req))
            }
        }

        route("/api/migrations/{migrationId}") {

            @KtorDescription(operationId = "getMigration", summary = "Get migration status")
            get("") {
                call.userId()
                val migrationId = call.parameters["migrationId"]
                    ?.let {
                        runCatching {
                            UUID.fromString(it)
                                .toKotlinUuid()
                        }.getOrNull()
                    }
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
                outgoing.trySend(Frame.Text(migrationJson.encodeToString(MigrationEvent.serializer(), event)))
                if (event is MigrationEvent.Completed || event is MigrationEvent.Failed) {
                    close(CloseReason(CloseReason.Codes.NORMAL, "Migration finished"))
                }
            }
        }
        try {
            incoming.consumeEach { }
        }
        finally {
            job.cancel()
        }
    }
}

private fun parseMigrationServerId(raw: String?): kotlin.uuid.Uuid? =
    raw?.let {
        runCatching {
            UUID.fromString(it)
                .toKotlinUuid()
        }.getOrNull()
    }
