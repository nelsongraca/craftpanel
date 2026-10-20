package io.craftpanel.master.routes

import io.craftpanel.master.auth.*
import io.craftpanel.master.grpc.DataServiceProxy
import io.craftpanel.master.grpc.UploadFrame
import io.craftpanel.master.routes.dto.*
import io.craftpanel.master.service.BadRequestException
import io.github.smiley4.ktoropenapi.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.io.asSink

// ── Route setup ───────────────────────────────────────────────────────────────

fun Route.filesRoutes(proxy: DataServiceProxy, maxFilesPerBatch: Int = 500) {
    authenticate(JWT_AUTH) {
        route("/api/servers/{id}/files") {
            get("", {
                operationId = "listServerFiles"
                summary = "List server files"
                request {
                    pathParameter<String>("id")
                    queryParameter<String>("path") { required = false }
                }
                response {
                    code(HttpStatusCode.OK) { body<ListFilesResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val auth = call.requireServerPermission(Permission.SERVER_FILES)
                val path = call.request.queryParameters["path"] ?: "/"
                call.respond(proxy.listFiles(auth.serverId, path))
            }

            get("/content", {
                operationId = "readServerFile"
                summary = "Read server file content"
                request {
                    pathParameter<String>("id")
                    queryParameter<String>("path") { required = true }
                }
                response {
                    code(HttpStatusCode.OK) { body<ReadFileResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val auth = call.requireServerPermission(Permission.SERVER_FILES)
                val path = call.request.queryParameters["path"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("path is required"))
                    return@get
                }
                val result = proxy.readFile(auth.serverId, path)
                if (result.encoding == "binary") {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("File is binary; use the download endpoint"))
                    return@get
                }
                call.respond(result)
            }

            put("/content", {
                operationId = "writeServerFile"
                summary = "Write server file content"
                request {
                    pathParameter<String>("id")
                    queryParameter<String>("path") { required = true }
                    body<String> { mediaTypes(ContentType.Text.Plain) }
                }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val auth = call.requireServerPermission(Permission.SERVER_FILES)
                val path = call.request.queryParameters["path"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("path is required"))
                    return@put
                }
                val content = call.receiveStream()
                    .readBytes()
                proxy.writeFile(auth.serverId, path, content)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/upload", {
                operationId = "uploadServerFile"
                summary = "Upload a file to the server"
                request {
                    pathParameter<String>("id")
                    multipartBody {
                        part<String>("path")
                        part("file", binaryFileSchema())
                    }
                }
                response {
                    code(HttpStatusCode.Created) { body<UploadResponse>() }
                    code(HttpStatusCode.BadRequest) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val auth = call.requireServerPermission(Permission.SERVER_FILES)
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
                        part.release()
                    }

                if (uploadPath.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("path field is required"))
                    return@post
                }

                val sizeBytes = proxy.uploadFile(auth.serverId, uploadPath, fileBytes)
                call.respond(HttpStatusCode.Created, UploadResponse(path = uploadPath, sizeBytes = sizeBytes))
            }

            post("/upload-batch", {
                operationId = "uploadServerFiles"
                summary = "Upload multiple files into a directory"
                request {
                    pathParameter<String>("id")
                    queryParameter<String>("path") { required = true }
                    multipartBody {
                        part("files", binaryFileArraySchema())
                    }
                }
                response {
                    code(HttpStatusCode.Created) { body<BatchUploadResponse>() }
                    code(HttpStatusCode.BadRequest) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                    code(HttpStatusCode.BadGateway) { body<ErrorResponse>() }
                }
            }) {
                val auth = call.requireServerPermission(Permission.SERVER_FILES)
                val directory = call.request.queryParameters["path"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("path is required"))
                    return@post
                }
                val results = proxy.uploadFiles(auth.serverId, uploadFrames(call, directory, maxFilesPerBatch))
                val uploaded = results.count { it.success }
                call.respond(
                    HttpStatusCode.Created,
                    BatchUploadResponse(
                        uploaded = uploaded,
                        failed = results.size - uploaded,
                        files = results.map { result ->
                            UploadedFileResultResponse(
                                path = result.path,
                                success = result.success,
                                sizeBytes = result.sizeBytes,
                                error = result.errorMessage.ifBlank { null }
                            )
                        }
                    )
                )
            }

            get("/download", {
                operationId = "downloadServerFile"
                summary = "Download a file from the server"
                request {
                    pathParameter<String>("id")
                    queryParameter<String>("path") { required = true }
                }
                response {
                    code(HttpStatusCode.OK) { binaryFileBody() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val auth = call.requireServerPermission(Permission.SERVER_FILES)
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
                call.respondBinaryFlow(proxy.downloadFile(auth.serverId, path))
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
                val auth = call.requireServerPermission(Permission.SERVER_FILES)
                val path = call.request.queryParameters["path"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("path is required"))
                    return@delete
                }
                val recursive = call.request.queryParameters["recursive"]?.lowercase() == "true"
                proxy.deleteFile(auth.serverId, path, recursive)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/move", {
                operationId = "moveServerFile"
                summary = "Move or rename a file"
                request {
                    pathParameter<String>("id")
                    body<MoveRequest>()
                }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val auth = call.requireServerPermission(Permission.SERVER_FILES)
                val req = call.receive<MoveRequest>()
                proxy.moveFile(auth.serverId, req.sourcePath, req.destinationPath)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/copy", {
                operationId = "copyServerFile"
                summary = "Copy a file or directory"
                request {
                    pathParameter<String>("id")
                    body<CopyRequest>()
                }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val auth = call.requireServerPermission(Permission.SERVER_FILES)
                val req = call.receive<CopyRequest>()
                proxy.copyFile(auth.serverId, req.sourcePath, req.destinationPath, req.recursive)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/mkdir", {
                operationId = "mkdirServerFile"
                summary = "Create a directory"
                request {
                    pathParameter<String>("id")
                    body<MkdirRequest>()
                }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val auth = call.requireServerPermission(Permission.SERVER_FILES)
                val req = call.receive<MkdirRequest>()
                proxy.makeDirectory(auth.serverId, req.path)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private const val UPLOAD_CHUNK_SIZE = 64 * 1024

/**
 * Streams the multipart `files` parts into upload frames.
 *
 * The destination of each entry is `[directory] + "/" + relativePath`, so a caller can
 * preserve folder structure by sending a filename that contains path segments (the
 * frontend wraps folder-upload files accordingly). No part is buffered in memory.
 */
private fun uploadFrames(call: ApplicationCall, directory: String, maxFilesPerBatch: Int): Flow<UploadFrame> = flow {
    var fileCount = 0
    call.receiveMultipart()
        .forEachPart { part ->
            try {
                if (part is PartData.FileItem) {
                    fileCount++
                    if (fileCount > maxFilesPerBatch) {
                        throw BadRequestException("Too many files in one batch (max $maxFilesPerBatch)")
                    }
                    val relativePath = sanitizeUploadPath(part.originalFileName)
                    emit(UploadFrame.FileStart(buildFilePath(directory, relativePath)))
                    val buffer = ByteArray(UPLOAD_CHUNK_SIZE)
                    val provider = part.provider()
                    while (true) {
                        val read = provider.readAvailable(buffer, 0, buffer.size)
                        if (read == -1) break
                        if (read > 0) emit(UploadFrame.Data(buffer.copyOf(read)))
                    }
                    emit(UploadFrame.FileEnd)
                }
            } finally {
                part.release()
            }
        }
}

/** Keeps nested segments for folder uploads but rejects absolute paths and traversal. */
private fun sanitizeUploadPath(name: String?): String {
    val segments = (name ?: "").replace('\\', '/')
        .trim()
        .trimStart('/')
        .split('/')
        .filter { it.isNotBlank() && it != "." }
    if (segments.isEmpty() || segments.any { it == ".." }) {
        throw BadRequestException("Invalid file name")
    }
    return segments.joinToString("/")
}

private fun buildFilePath(directory: String, relativePath: String): String {
    val dir = directory.trim()
        .ifEmpty { "/" }
    return if (dir == "/") "/$relativePath" else "${dir.trimEnd('/')}/$relativePath"
}
