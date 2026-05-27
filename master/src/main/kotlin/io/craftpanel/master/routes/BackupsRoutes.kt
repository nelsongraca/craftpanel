package io.craftpanel.master.routes

import com.craftpanel.agent.v1.MasterMessage
import com.craftpanel.agent.v1.deleteBackupCommand
import com.craftpanel.agent.v1.masterMessage
import com.craftpanel.agent.v1.triggerBackupCommand
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.database.schema.Backups
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.grpc.DataServiceProxy
import io.craftpanel.master.util.toKotlinUuid
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

private val CRON_REGEX = Regex("""^(\*|[0-9,\-*/]+)\s+(\*|[0-9,\-*/]+)\s+(\*|[0-9,\-*/]+)\s+(\*|[0-9,\-*/]+)\s+(\*|[0-9,\-*/]+)$""")

@Serializable
data class BackupResponse(
    val id: String,
    @SerialName("server_id") val serverId: String,
    @SerialName("node_id") val nodeId: String,
    val trigger: String,
    val status: String,
    @SerialName("file_path") val filePath: String?,
    @SerialName("size_bytes") val sizeBytes: Long?,
    @SerialName("error_message") val errorMessage: String?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("completed_at") val completedAt: String?,
)

@Serializable
data class BackupScheduleResponse(
    @SerialName("backup_schedule") val backupSchedule: String?,
    @SerialName("backup_max_count") val backupMaxCount: Int,
)

@Serializable
data class PutBackupScheduleRequest(
    @SerialName("backup_schedule") val backupSchedule: String?,
    @SerialName("backup_max_count") val backupMaxCount: Int? = null,
)

fun Route.backupsRoutes(sendToNode: (String, MasterMessage) -> Boolean, dataServiceProxy: DataServiceProxy) {
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
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                val id = parseBackupServerId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                    return@get
                }
                val (serverRow) = resolveServer(id) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                    return@get
                }
                val serverIdJava = UUID.fromString(id.toString())
                val netIdJava = serverRow[Servers.networkId]?.let { UUID.fromString(it.toString()) }
                if (!PermissionResolver.hasPermission(userId, "server.backup", serverId = serverIdJava, networkId = netIdJava)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@get
                }

                val backups = transaction {
                    Backups.selectAll()
                        .where { Backups.serverId eq id }
                        .orderBy(Backups.createdAt, SortOrder.DESC)
                        .map { it.toBackupResponse() }
                }
                call.respond(mapOf("backups" to backups))
            }

            post("", {
                operationId = "triggerBackup"
                summary = "Trigger server backup"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.Accepted) { body<BackupResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.BadGateway) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                val id = parseBackupServerId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                    return@post
                }
                val (serverRow) = resolveServer(id) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                    return@post
                }
                val serverIdJava = UUID.fromString(id.toString())
                val netIdJava = serverRow[Servers.networkId]?.let { UUID.fromString(it.toString()) }
                if (!PermissionResolver.hasPermission(userId, "server.backup", serverId = serverIdJava, networkId = netIdJava)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@post
                }

                val nodeKotlinId = serverRow[Servers.nodeId]
                val nodeRow = transaction { Nodes.selectAll().where { Nodes.id eq nodeKotlinId }.firstOrNull() } ?: run {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("Node not found"))
                    return@post
                }
                val nodeId = nodeKotlinId.toString()

                val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

                val backupResponse = transaction {
                    val maxCount = serverRow[Servers.backupMaxCount]

                    // Enforce retention: delete oldest COMPLETED backups beyond maxCount-1
                    val completed = Backups.selectAll()
                        .where { (Backups.serverId eq id) and (Backups.status eq "COMPLETED") }
                        .orderBy(Backups.createdAt, SortOrder.ASC)
                        .toList()
                    if (completed.size >= maxCount) {
                        val toDelete = completed.take(completed.size - maxCount + 1)
                        for (old in toDelete) {
                            val filePath = old[Backups.filePath]
                            val backupNodeId = old[Backups.nodeId].toString()
                            if (!filePath.isNullOrEmpty()) {
                                sendToNode(backupNodeId, masterMessage {
                                    deleteBackup = deleteBackupCommand {
                                        backupId = old[Backups.id].toString()
                                        this.filePath = filePath
                                    }
                                })
                            }
                            Backups.deleteWhere { Backups.id eq old[Backups.id] }
                        }
                    }

                    val destPath = "${nodeRow[Nodes.dataPath]}/backups/${UUID.randomUUID()}.tar.gz"

                    val backupId = Backups.insert {
                        it[Backups.serverId] = id
                        it[Backups.nodeId] = nodeKotlinId
                        it[Backups.trigger] = "MANUAL"
                        it[Backups.status] = "IN_PROGRESS"
                    }[Backups.id]

                    val row = Backups.selectAll().where { Backups.id eq backupId }.first()

                    val sent = sendToNode(nodeId, masterMessage {
                        triggerBackup = triggerBackupCommand {
                            this.backupId = backupId.toString()
                            serverId = id.toString()
                            containerName = "craftpanel-$id"
                            destinationPath = destPath
                        }
                    })

                    if (!sent) {
                        Backups.update({ Backups.id eq backupId }) {
                            it[Backups.status] = "FAILED"
                            it[Backups.errorMessage] = "Agent not connected"
                            it[Backups.completedAt] = now
                        }
                    }

                    row.toBackupResponse()
                }

                if (backupResponse.status == "FAILED" && backupResponse.errorMessage == "Agent not connected") {
                    call.respond(HttpStatusCode.BadGateway, ErrorResponse("Agent not connected"))
                    return@post
                }

                call.respond(HttpStatusCode.Accepted, backupResponse)
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
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                val id = parseBackupServerId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                    return@delete
                }
                val backupKotlinId = call.parameters["backupId"]
                    ?.let { runCatching { UUID.fromString(it).toKotlinUuid() }.getOrNull() }
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid backup ID"))
                        return@delete
                    }

                val (serverRow) = resolveServer(id) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                    return@delete
                }
                val serverIdJava = UUID.fromString(id.toString())
                val netIdJava = serverRow[Servers.networkId]?.let { UUID.fromString(it.toString()) }
                if (!PermissionResolver.hasPermission(userId, "server.backup", serverId = serverIdJava, networkId = netIdJava)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@delete
                }

                val backup = transaction {
                    Backups.selectAll()
                        .where { (Backups.id eq backupKotlinId) and (Backups.serverId eq id) }
                        .firstOrNull()
                } ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Backup not found"))
                    return@delete
                }

                if (backup[Backups.status] == "IN_PROGRESS") {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot delete a backup that is in progress"))
                    return@delete
                }

                val filePath = backup[Backups.filePath]
                val backupNodeId = backup[Backups.nodeId].toString()
                if (!filePath.isNullOrEmpty()) {
                    sendToNode(backupNodeId, masterMessage {
                        deleteBackup = deleteBackupCommand {
                            backupId = backupKotlinId.toString()
                            this.filePath = filePath
                        }
                    })
                }

                transaction { Backups.deleteWhere { Backups.id eq backupKotlinId } }
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
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                val id = parseBackupServerId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                    return@get
                }
                val backupKotlinId = call.parameters["backupId"]
                    ?.let { runCatching { UUID.fromString(it).toKotlinUuid() }.getOrNull() }
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid backup ID"))
                        return@get
                    }

                val (serverRow) = resolveServer(id) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                    return@get
                }
                val serverIdJava = UUID.fromString(id.toString())
                val netIdJava = serverRow[Servers.networkId]?.let { UUID.fromString(it.toString()) }
                if (!PermissionResolver.hasPermission(userId, "server.export", serverId = serverIdJava, networkId = netIdJava)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@get
                }

                val backup = transaction {
                    Backups.selectAll()
                        .where { (Backups.id eq backupKotlinId) and (Backups.serverId eq id) }
                        .firstOrNull()
                } ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Backup not found"))
                    return@get
                }

                if (backup[Backups.status] != "COMPLETED") {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("Backup is not in COMPLETED status"))
                    return@get
                }

                val filePath = backup[Backups.filePath] ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Backup file path not available"))
                    return@get
                }

                call.respondOutputStream(ContentType.Application.OctetStream) {
                    dataServiceProxy.downloadFile(id.toString(), filePath).collect { chunk ->
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
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                val id = parseBackupServerId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                    return@get
                }
                val (serverRow) = resolveServer(id) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                    return@get
                }
                val serverIdJava = UUID.fromString(id.toString())
                val netIdJava = serverRow[Servers.networkId]?.let { UUID.fromString(it.toString()) }
                if (!PermissionResolver.hasPermission(userId, "server.backup", serverId = serverIdJava, networkId = netIdJava)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@get
                }
                call.respond(BackupScheduleResponse(
                    backupSchedule = serverRow[Servers.backupSchedule],
                    backupMaxCount = serverRow[Servers.backupMaxCount],
                ))
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
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                val id = parseBackupServerId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                    return@put
                }
                val (serverRow) = resolveServer(id) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                    return@put
                }
                val serverIdJava = UUID.fromString(id.toString())
                val netIdJava = serverRow[Servers.networkId]?.let { UUID.fromString(it.toString()) }
                if (!PermissionResolver.hasPermission(userId, "server.backup", serverId = serverIdJava, networkId = netIdJava)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@put
                }

                val req = call.receive<PutBackupScheduleRequest>()

                if (req.backupSchedule != null && !CRON_REGEX.matches(req.backupSchedule)) {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("Invalid cron expression"))
                    return@put
                }
                if (req.backupMaxCount != null && req.backupMaxCount < 1) {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("backup_max_count must be at least 1"))
                    return@put
                }

                transaction {
                    Servers.update({ Servers.id eq id }) {
                        it[Servers.backupSchedule] = req.backupSchedule
                        if (req.backupMaxCount != null) it[Servers.backupMaxCount] = req.backupMaxCount
                        it[Servers.updatedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                    }
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private data class ResolvedServer(val row: org.jetbrains.exposed.v1.core.ResultRow)

private operator fun ResolvedServer.component1() = row

private fun resolveServer(id: kotlin.uuid.Uuid): ResolvedServer? = transaction {
    Servers.selectAll().where { Servers.id eq id }.firstOrNull()?.let { ResolvedServer(it) }
}

private fun parseBackupServerId(raw: String?): kotlin.uuid.Uuid? =
    raw?.let { runCatching { UUID.fromString(it).toKotlinUuid() }.getOrNull() }

private fun org.jetbrains.exposed.v1.core.ResultRow.toBackupResponse() = BackupResponse(
    id = this[Backups.id].toString(),
    serverId = this[Backups.serverId].toString(),
    nodeId = this[Backups.nodeId].toString(),
    trigger = this[Backups.trigger],
    status = this[Backups.status],
    filePath = this[Backups.filePath],
    sizeBytes = this[Backups.sizeBytes],
    errorMessage = this[Backups.errorMessage],
    createdAt = this[Backups.createdAt].toString(),
    completedAt = this[Backups.completedAt]?.toString(),
)
