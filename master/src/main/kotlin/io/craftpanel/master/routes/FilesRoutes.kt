package io.craftpanel.master.routes

import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.JWT_AUTH
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.grpc.DataServiceProxy
import io.craftpanel.master.util.toKotlinUuid
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readTo
import io.ktor.utils.io.writeFully
import kotlinx.io.asSink
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.util.*

// ── Response types ────────────────────────────────────────────────────────────

@Serializable
data class FileEntryResponse(
    val name: String,
    @SerialName("is_directory") val isDirectory: Boolean,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("modified_at") val modifiedAt: String?,
    val permissions: String,
)

@Serializable
data class ListFilesResponse(
    val path: String,
    val entries: List<FileEntryResponse>,
)

@Serializable
data class ReadFileResponse(
    val path: String,
    val content: String,
    val encoding: String,
)

@Serializable
data class UploadResponse(
    val path: String,
    @SerialName("size_bytes") val sizeBytes: Long,
)

@Serializable
data class MoveRequest(
    @SerialName("source_path") val sourcePath: String,
    @SerialName("destination_path") val destinationPath: String,
)

@Serializable
data class CopyRequest(
    @SerialName("source_path") val sourcePath: String,
    @SerialName("destination_path") val destinationPath: String,
    val recursive: Boolean = false,
)

@Serializable
data class MkdirRequest(val path: String)

// ── Route setup ───────────────────────────────────────────────────────────────

fun Route.filesRoutes(proxy: DataServiceProxy) {
    authenticate(JWT_AUTH) {
        route("/api/servers/{id}/files") {

            get("", {
                operationId = "listServerFiles"
                summary = "List server files"
                request { pathParameter<String>("id"); queryParameter<String>("path") { required = false } }
                response {
                    code(HttpStatusCode.OK) { body<ListFilesResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val (_, serverId, _) = extractAndAuthorize(call) ?: return@get
                val path = call.request.queryParameters["path"] ?: "/"
                try {
                    val result = proxy.listFiles(serverId, path)
                    call.respond(
                        ListFilesResponse(
                            path = path,
                            entries = result.entriesList.map { e ->
                                FileEntryResponse(
                                    name = e.name,
                                    isDirectory = e.isDirectory,
                                    sizeBytes = e.sizeBytes,
                                    modifiedAt = if (e.hasModifiedAt()) Instant.ofEpochSecond(e.modifiedAt.seconds)
                                        .toString()
                                    else null,
                                    permissions = e.permissions,
                                )
                            }
                        ))
                }
                catch (e: Exception) {
                    call.respond(HttpStatusCode.BadGateway, ErrorResponse("Agent error: ${e.message}"))
                }
            }

            get("/content", {
                operationId = "readServerFile"
                summary = "Read server file content"
                request { pathParameter<String>("id"); queryParameter<String>("path") { required = true } }
                response {
                    code(HttpStatusCode.OK) { body<ReadFileResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val (_, serverId, _) = extractAndAuthorize(call) ?: return@get
                val path = call.request.queryParameters["path"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("path is required"))
                    return@get
                }
                try {
                    val result = proxy.readFile(serverId, path)
                    if (result.encoding == "binary") {
                        call.respond(HttpStatusCode.Conflict, ErrorResponse("File is binary; use the download endpoint"))
                        return@get
                    }
                    call.respond(ReadFileResponse(path = path, content = result.content.toStringUtf8(), encoding = result.encoding))
                }
                catch (e: io.grpc.StatusException) {
                    call.respondGrpcFileError(e)
                }
            }

            put("/content", {
                operationId = "writeServerFile"
                summary = "Write server file content"
                request { pathParameter<String>("id"); queryParameter<String>("path") { required = true } }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val (_, serverId, _) = extractAndAuthorize(call) ?: return@put
                val path = call.request.queryParameters["path"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("path is required"))
                    return@put
                }
                val content = call.receiveStream()
                    .readBytes()
                try {
                    proxy.writeFile(serverId, path, content)
                    call.respond(HttpStatusCode.NoContent)
                }
                catch (e: Exception) {
                    call.respond(HttpStatusCode.BadGateway, ErrorResponse("Agent error: ${e.message}"))
                }
            }

            post("/upload", {
                operationId = "uploadServerFile"
                summary = "Upload a file to the server"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.Created) { body<UploadResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val (_, serverId, _) = extractAndAuthorize(call) ?: return@post
                var uploadPath = ""
                var fileBytes = byteArrayOf()

                call.receiveMultipart()
                    .forEachPart { part ->
                        when {
                            part is PartData.FormItem && part.name == "path" -> uploadPath = part.value
                            part is PartData.FileItem && part.name == "file" -> {
                                val baos = java.io.ByteArrayOutputStream()
                                part.provider()
                                    .readTo(baos.asSink())
                                fileBytes = baos.toByteArray()
                            }
                        }
                        part.dispose()
                    }

                if (uploadPath.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("path field is required"))
                    return@post
                }

                try {
                    val sizeBytes = proxy.uploadFile(serverId, uploadPath, fileBytes)
                    call.respond(HttpStatusCode.Created, UploadResponse(path = uploadPath, sizeBytes = sizeBytes))
                }
                catch (e: Exception) {
                    call.respond(HttpStatusCode.BadGateway, ErrorResponse("Agent error: ${e.message}"))
                }
            }

            get("/download", {
                operationId = "downloadServerFile"
                summary = "Download a file from the server"
                request { pathParameter<String>("id"); queryParameter<String>("path") { required = true } }
                response {
                    code(HttpStatusCode.OK) { }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val (_, serverId, _) = extractAndAuthorize(call) ?: return@get
                val path = call.request.queryParameters["path"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("path is required"))
                    return@get
                }
                val filename = path.substringAfterLast('/')
                    .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    .ifEmpty { "download" }
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, filename)
                        .toString()
                )
                try {
                    call.respondBytesWriter(contentType = ContentType.Application.OctetStream) {
                        proxy.downloadFile(serverId, path)
                            .collect { bytes -> writeFully(bytes) }
                    }
                }
                catch (e: Exception) {
                    call.respond(HttpStatusCode.BadGateway, ErrorResponse("Agent error: ${e.message}"))
                }
            }

            delete("", {
                operationId = "deleteServerFile"
                summary = "Delete a file or directory"
                request {
                    pathParameter<String>("id")
                    queryParameter<String>("path") { required = true }
                    queryParameter<Boolean>("recursive") { required = false }
                }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val (_, serverId, _) = extractAndAuthorize(call) ?: return@delete
                val path = call.request.queryParameters["path"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("path is required"))
                    return@delete
                }
                val recursive = call.request.queryParameters["recursive"]?.lowercase() == "true"
                try {
                    proxy.deleteFile(serverId, path, recursive)
                    call.respond(HttpStatusCode.NoContent)
                }
                catch (e: io.grpc.StatusException) {
                    when (e.status.code) {
                        io.grpc.Status.Code.NOT_FOUND           -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Path not found"))
                        io.grpc.Status.Code.FAILED_PRECONDITION -> call.respond(HttpStatusCode.Conflict, ErrorResponse("Directory is not empty; use recursive=true"))
                        else                                    -> call.respond(HttpStatusCode.BadGateway, ErrorResponse("Agent error: ${e.message}"))
                    }
                }
            }

            post("/move", {
                operationId = "moveServerFile"
                summary = "Move or rename a file"
                request { pathParameter<String>("id"); body<MoveRequest>() }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val (_, serverId, _) = extractAndAuthorize(call) ?: return@post
                val req = call.receive<MoveRequest>()
                try {
                    proxy.moveFile(serverId, req.sourcePath, req.destinationPath)
                    call.respond(HttpStatusCode.NoContent)
                }
                catch (e: io.grpc.StatusException) {
                    call.respondGrpcMoveError(e)
                }
            }

            post("/copy", {
                operationId = "copyServerFile"
                summary = "Copy a file or directory"
                request { pathParameter<String>("id"); body<CopyRequest>() }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val (_, serverId, _) = extractAndAuthorize(call) ?: return@post
                val req = call.receive<CopyRequest>()
                try {
                    proxy.copyFile(serverId, req.sourcePath, req.destinationPath, req.recursive)
                    call.respond(HttpStatusCode.NoContent)
                }
                catch (e: io.grpc.StatusException) {
                    call.respondGrpcMoveError(e)
                }
            }

            post("/mkdir", {
                operationId = "mkdirServerFile"
                summary = "Create a directory"
                request { pathParameter<String>("id"); body<MkdirRequest>() }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val (_, serverId, _) = extractAndAuthorize(call) ?: return@post
                val req = call.receive<MkdirRequest>()
                try {
                    proxy.makeDirectory(serverId, req.path)
                    call.respond(HttpStatusCode.NoContent)
                }
                catch (e: Exception) {
                    call.respond(HttpStatusCode.BadGateway, ErrorResponse("Agent error: ${e.message}"))
                }
            }
        }
    }
}

private data class AuthContext(val userId: UUID, val serverId: String, val networkId: UUID?)

private suspend fun ApplicationCall.respondGrpcFileError(e: io.grpc.StatusException) {
    when (e.status.code) {
        io.grpc.Status.Code.NOT_FOUND        -> respond(HttpStatusCode.NotFound, ErrorResponse("File not found"))
        io.grpc.Status.Code.INVALID_ARGUMENT -> respond(HttpStatusCode.Conflict, ErrorResponse("Path is a directory"))
        else                                 -> respond(HttpStatusCode.BadGateway, ErrorResponse("Agent error: ${e.message}"))
    }
}

private suspend fun ApplicationCall.respondGrpcMoveError(e: io.grpc.StatusException) {
    when (e.status.code) {
        io.grpc.Status.Code.NOT_FOUND      -> respond(HttpStatusCode.NotFound, ErrorResponse("Source not found"))
        io.grpc.Status.Code.ALREADY_EXISTS -> respond(HttpStatusCode.Conflict, ErrorResponse("Destination already exists"))
        else                               -> respond(HttpStatusCode.BadGateway, ErrorResponse("Agent error: ${e.message}"))
    }
}

private suspend fun extractAndAuthorize(
    call: ApplicationCall,
): AuthContext? {
    val principal = call.principal<JWTPrincipal>()!!
    val userId = UUID.fromString(principal.payload.subject)

    val rawId = call.parameters["id"] ?: run {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing server ID"))
        return null
    }

    val info = transaction {
        val kotlinId = runCatching {
            UUID.fromString(rawId)
                .toKotlinUuid()
        }.getOrNull() ?: return@transaction null
        val row = Servers.selectAll()
            .where { Servers.id eq kotlinId }
            .firstOrNull() ?: return@transaction null
        val serverId = UUID.fromString(kotlinId.toString())
        val networkId = row[Servers.networkId]?.let { UUID.fromString(it.toString()) }
        Triple(serverId, networkId, kotlinId)
    } ?: run {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
        return null
    }

    val (serverId, networkId, _) = info

    if (!PermissionResolver.hasPermission(userId, Permission.SERVER_FILES, serverId, networkId)) {
        call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
        return null
    }

    return AuthContext(userId, rawId, networkId)
}
