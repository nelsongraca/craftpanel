package io.craftpanel.master.routes

import io.craftpanel.master.auth.JWT_AUTH
import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.requireServerPermission
import io.craftpanel.master.grpc.DataServiceProxy
import io.craftpanel.master.routes.dto.CopyRequest
import io.craftpanel.master.routes.dto.ListFilesResponse
import io.craftpanel.master.routes.dto.MkdirRequest
import io.craftpanel.master.routes.dto.MoveRequest
import io.craftpanel.master.routes.dto.ReadFileResponse
import io.craftpanel.master.routes.dto.UploadResponse
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readTo
import kotlinx.io.asSink

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
                val auth = call.requireServerPermission(Permission.SERVER_FILES)
                val path = call.request.queryParameters["path"] ?: "/"
                call.respond(proxy.listFiles(auth.serverId, path))
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
                        part<ByteArray>("file")
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

            get("/download", {
                operationId = "downloadServerFile"
                summary = "Download a file from the server"
                request { pathParameter<String>("id"); queryParameter<String>("path") { required = true } }
                response {
                    code(HttpStatusCode.OK) { body<ByteArray>() }
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
                val downloadFlow = proxy.downloadFile(auth.serverId, path)
                val chunks = mutableListOf<ByteArray>()
                downloadFlow.collect { chunks.add(it) }
                val byteList = chunks.flatMap { chunk -> chunk.map { it.toInt() and 0xFF } }
                call.respond(byteList)
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
                request { pathParameter<String>("id"); body<MoveRequest>() }
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
                request { pathParameter<String>("id"); body<CopyRequest>() }
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
                request { pathParameter<String>("id"); body<MkdirRequest>() }
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
