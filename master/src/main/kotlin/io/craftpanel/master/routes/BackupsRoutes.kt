package io.craftpanel.master.routes

import com.craftpanel.agent.v1.MasterMessage
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.grpc.DataServiceProxy
import io.craftpanel.master.service.BackupResponse
import io.craftpanel.master.service.BackupScheduleResponse
import io.craftpanel.master.service.BackupService
import io.craftpanel.master.service.PutBackupScheduleRequest
import io.craftpanel.master.util.toKotlinUuid
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import java.util.UUID

fun Route.backupsRoutes(backupService: BackupService) {
    authenticate("auth-jwt") {
        route("/api/servers/{id}/backups") {

            get("", {
                operationId = "listBackups"
                summary = "List server backups"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.OK) { body<Map<String, List<BackupResponse>>>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseBackupServerId(call.parameters["id"])
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val scope = backupService.getServerScope(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                val serverIdJava = UUID.fromString(id.toString())
                if (!PermissionResolver.hasPermission(userId, "server.backup", serverId = serverIdJava, networkId = scope.networkId))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                call.respond(mapOf("backups" to backupService.listBackups(id)))
            }

            post("", {
                operationId = "triggerBackup"
                summary = "Trigger server backup"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.Accepted) { body<BackupResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.BadGateway) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseBackupServerId(call.parameters["id"])
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val scope = backupService.getServerScope(id)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                val serverIdJava = UUID.fromString(id.toString())
                if (!PermissionResolver.hasPermission(userId, "server.backup", serverId = serverIdJava, networkId = scope.networkId))
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                call.respond(HttpStatusCode.Accepted, backupService.triggerBackup(id))
            }

            delete("/{backupId}", {
                operationId = "deleteBackup"
                summary = "Delete server backup"
                request { pathParameter<String>("id"); pathParameter<String>("backupId") }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseBackupServerId(call.parameters["id"])
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val backupId = call.parameters["backupId"]?.let { runCatching { UUID.fromString(it).toKotlinUuid() }.getOrNull() }
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid backup ID"))
                val scope = backupService.getServerScope(id)
                    ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                val serverIdJava = UUID.fromString(id.toString())
                if (!PermissionResolver.hasPermission(userId, "server.backup", serverId = serverIdJava, networkId = scope.networkId))
                    return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                backupService.deleteBackup(id, backupId)
                call.respond(HttpStatusCode.NoContent)
            }

            get("/{backupId}/download", {
                operationId = "downloadBackup"
                summary = "Download backup file"
                request { pathParameter<String>("id"); pathParameter<String>("backupId") }
                response {
                    code(HttpStatusCode.OK) { }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseBackupServerId(call.parameters["id"])
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val backupId = call.parameters["backupId"]?.let { runCatching { UUID.fromString(it).toKotlinUuid() }.getOrNull() }
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid backup ID"))
                val scope = backupService.getServerScope(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                val serverIdJava = UUID.fromString(id.toString())
                if (!PermissionResolver.hasPermission(userId, "server.export", serverId = serverIdJava, networkId = scope.networkId))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val info = backupService.resolveDownload(id, backupId)
                call.respondOutputStream(ContentType.Application.OctetStream) {
                    backupService.downloadStream(info).collect { chunk ->
                        write(chunk.data.toByteArray())
                        flush()
                    }
                }
            }
        }

        route("/api/servers/{id}/backup-schedule") {

            get("", {
                operationId = "getBackupSchedule"
                summary = "Get server backup schedule"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.OK) { body<BackupScheduleResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseBackupServerId(call.parameters["id"])
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val scope = backupService.getServerScope(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                val serverIdJava = UUID.fromString(id.toString())
                if (!PermissionResolver.hasPermission(userId, "server.backup", serverId = serverIdJava, networkId = scope.networkId))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                call.respond(backupService.getSchedule(id))
            }

            put("", {
                operationId = "updateBackupSchedule"
                summary = "Update server backup schedule"
                request { pathParameter<String>("id"); body<PutBackupScheduleRequest>() }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseBackupServerId(call.parameters["id"])
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val scope = backupService.getServerScope(id)
                    ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                val serverIdJava = UUID.fromString(id.toString())
                if (!PermissionResolver.hasPermission(userId, "server.backup", serverId = serverIdJava, networkId = scope.networkId))
                    return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<PutBackupScheduleRequest>()
                backupService.updateSchedule(id, req)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun parseBackupServerId(raw: String?): kotlin.uuid.Uuid? =
    raw?.let { runCatching { UUID.fromString(it).toKotlinUuid() }.getOrNull() }
