package io.craftpanel.master.routes

import io.craftpanel.master.auth.*
import io.craftpanel.master.service.*
import io.github.smiley4.ktoropenapi.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

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
                val auth = call.requireServerPermission(Permission.SERVER_BACKUP)
                call.respond(BackupsListResponse(backupService.listBackups(auth.serverId)))
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
                val auth = call.requireServerPermission(Permission.SERVER_BACKUP)
                call.respond(HttpStatusCode.Accepted, backupService.triggerBackup(auth.serverId))
            }

            delete("/{backupId}", {
                operationId = "deleteBackup"
                summary = "Delete server backup"
                request {
                    pathParameter<String>("id")
                    pathParameter<String>("backupId")
                }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val auth = call.requireServerPermission(Permission.SERVER_BACKUP)
                val backupId = call.parameters["backupId"]?.let {
                    runCatching { Uuid.parse(it) }.getOrNull()
                }
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid backup ID"))
                backupService.deleteBackup(auth.serverId, backupId)
                call.respond(HttpStatusCode.NoContent)
            }

            get("/{backupId}/download", {
                operationId = "downloadBackup"
                summary = "Download backup file"
                request {
                    pathParameter<String>("id")
                    pathParameter<String>("backupId")
                }
                response {
                    code(HttpStatusCode.OK) { binaryFileBody() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val auth = call.requireServerPermission(Permission.SERVER_EXPORT)
                val backupId = call.parameters["backupId"]?.let {
                    runCatching { Uuid.parse(it) }.getOrNull()
                }
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid backup ID"))
                val info = backupService.resolveDownload(auth.serverId, backupId)
                call.respondBinaryFlow(backupService.downloadStream(info))
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
                val auth = call.requireServerPermission(Permission.SERVER_BACKUP)
                call.respond(backupService.getSchedule(auth.serverId))
            }

            put("", {
                operationId = "updateBackupSchedule"
                summary = "Update server backup schedule"
                request {
                    pathParameter<String>("id")
                    body<PutBackupScheduleRequest>()
                }
                response {
                    code(HttpStatusCode.OK) { body<BackupScheduleResponse>() }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val auth = call.requireServerPermission(Permission.SERVER_BACKUP)
                val req = call.receive<PutBackupScheduleRequest>()
                backupService.updateSchedule(auth.serverId, req)
                call.respond(HttpStatusCode.OK, backupService.getSchedule(auth.serverId))
            }
        }
    }
}
