package io.craftpanel.master.routes

import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.JWT_AUTH
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.service.BackupResponse
import io.craftpanel.master.service.BackupScheduleResponse
import io.craftpanel.master.service.BackupService
import io.craftpanel.master.service.PutBackupScheduleRequest
import io.craftpanel.master.util.toKotlinUuid
import kotlinx.serialization.Serializable
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.writeFully
import java.util.*

@Serializable
data class BackupsListResponse(val backups: List<BackupResponse>)

fun Route.backupsRoutes(backupService: BackupService) {
    authenticate(JWT_AUTH) {
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
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_BACKUP, serverId = serverIdJava, networkId = scope.networkId))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                call.respond(BackupsListResponse(backupService.listBackups(id)))
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
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_BACKUP, serverId = serverIdJava, networkId = scope.networkId))
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
                val backupId = call.parameters["backupId"]?.let {
                    runCatching {
                        UUID.fromString(it)
                            .toKotlinUuid()
                    }.getOrNull()
                }
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid backup ID"))
                val scope = backupService.getServerScope(id)
                    ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                val serverIdJava = UUID.fromString(id.toString())
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_BACKUP, serverId = serverIdJava, networkId = scope.networkId))
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
                val backupId = call.parameters["backupId"]?.let {
                    runCatching {
                        UUID.fromString(it)
                            .toKotlinUuid()
                    }.getOrNull()
                }
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid backup ID"))
                val scope = backupService.getServerScope(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                val serverIdJava = UUID.fromString(id.toString())
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_EXPORT, serverId = serverIdJava, networkId = scope.networkId))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val info = backupService.resolveDownload(id, backupId)
                call.respondBytesWriter(contentType = ContentType.Application.OctetStream) {
                    backupService.downloadStream(info).collect { bytes -> writeFully(bytes) }
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
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_BACKUP, serverId = serverIdJava, networkId = scope.networkId))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                call.respond(backupService.getSchedule(id))
            }

            put("", {
                operationId = "updateBackupSchedule"
                summary = "Update server backup schedule"
                request { pathParameter<String>("id"); body<PutBackupScheduleRequest>() }
                response {
                    code(HttpStatusCode.OK) { body<BackupScheduleResponse>() }
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
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_BACKUP, serverId = serverIdJava, networkId = scope.networkId))
                    return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<PutBackupScheduleRequest>()
                backupService.updateSchedule(id, req)
                call.respond(HttpStatusCode.OK, backupService.getSchedule(id))
            }
        }
    }
}

private fun parseBackupServerId(raw: String?): kotlin.uuid.Uuid? =
    raw?.let {
        runCatching {
            UUID.fromString(it)
                .toKotlinUuid()
        }.getOrNull()
    }
