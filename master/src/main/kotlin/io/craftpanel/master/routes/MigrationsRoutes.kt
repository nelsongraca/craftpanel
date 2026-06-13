@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.craftpanel.master.routes

import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.JWT_AUTH
import io.craftpanel.master.auth.requireServerPermission
import io.craftpanel.master.service.MigrateRequest
import io.craftpanel.master.service.MigrationEvent
import io.craftpanel.master.service.MigrationResponse
import io.craftpanel.master.service.MigrationService
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

@Serializable
data class MigrationsListResponse(val migrations: List<MigrationResponse>)

private val migrationJson = Json { classDiscriminator = "type"; namingStrategy = JsonNamingStrategy.SnakeCase }

fun Route.migrationsRoutes(migrationService: MigrationService) {
    authenticate(JWT_AUTH) {
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
                val auth = call.requireServerPermission(Permission.SERVER_MIGRATE)
                call.respond(MigrationsListResponse(migrationService.listMigrations(auth.serverId)))
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
                val auth = call.requireServerPermission(Permission.SERVER_MIGRATE)
                val req = call.receive<MigrateRequest>()
                call.respond(HttpStatusCode.Accepted, migrationService.startMigration(auth.serverId, req))
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
                    ?.let {
                        runCatching {
                            Uuid.parse(it)

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
