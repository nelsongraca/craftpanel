package io.craftpanel.master.grpc

import io.craftpanel.proto.*
import com.google.protobuf.ByteString
import io.craftpanel.master.database.schema.Servers
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction


/**
 * Routes file and console operations from REST/WebSocket routes to the connected agent
 * via the multiplexed ControlService stream and the BulkDataService for large transfers.
 *
 * Master never dials agents — all connections are agent-initiated.
 */
class DataServiceProxy(
    private val controlService: ControlServiceImpl,
    private val bulkService: BulkDataServiceImpl,
) {

    private fun lookupNodeId(serverId: String): String = transaction {
        val id = runCatching {
            Uuid.parse(serverId)
                
        }.getOrElse {
            error("Invalid server ID: $serverId")
        }
        val server = Servers.selectAll()
            .where { Servers.id eq id }
            .firstOrNull() ?: error("Server $serverId not found")
        server[Servers.nodeId].toString()
    }

    private data class ServerLookup(val nodeId: String, val status: String)

    private fun lookupServer(serverId: String): ServerLookup = transaction {
        val id = runCatching {
            Uuid.parse(serverId)
                
        }.getOrElse {
            error("Invalid server ID: $serverId")
        }
        val server = Servers.selectAll()
            .where { Servers.id eq id }
            .firstOrNull() ?: error("Server $serverId not found")
        ServerLookup(server[Servers.nodeId].toString(), server[Servers.status])
    }

    // ── Console ───────────────────────────────────────────────────────────────

    /**
     * Open a console session for the given server.
     * [input] is a flow of raw bytes from the browser terminal.
     * Returns a flow of raw bytes to be sent to the browser.
     */
    fun console(serverId: String, input: Flow<ByteArray>): Flow<ByteArray> {
        val (nodeId, status) = lookupServer(serverId)
        if (status == "STOPPED") return kotlinx.coroutines.flow.emptyFlow()
        return controlService.openConsole(nodeId, serverId, input)
            .map { output -> output.data.toByteArray() }
    }

    // ── File operations ───────────────────────────────────────────────────────

    suspend fun listFiles(serverId: String, path: String): ListFilesResponse {
        val nodeId = lookupNodeId(serverId)
        val reqId = Uuid.random()
            .toString()
        val response = controlService.sendAndAwait(nodeId, reqId, masterMessage {
            listFiles = listFilesRequest { requestId = reqId; this.serverId = serverId; this.path = path }
        })
        val r = response.listFilesResponse
        if (r.errorMessage.isNotBlank()) error(r.errorMessage)
        return r
    }

    suspend fun readFile(serverId: String, path: String): ReadFileResponse {
        val nodeId = lookupNodeId(serverId)
        val reqId = Uuid.random()
            .toString()
        val response = controlService.sendAndAwait(nodeId, reqId, masterMessage {
            readFile = readFileRequest { requestId = reqId; this.serverId = serverId; this.path = path }
        })
        val r = response.readFileResponse
        if (r.errorMessage.isNotBlank()) error(r.errorMessage)
        return r
    }

    suspend fun writeFile(serverId: String, path: String, content: ByteArray) {
        val nodeId = lookupNodeId(serverId)
        val reqId = Uuid.random()
            .toString()
        val response = controlService.sendAndAwait(nodeId, reqId, masterMessage {
            writeFile = writeFileRequest {
                requestId = reqId; this.serverId = serverId; this.path = path
                this.content = ByteString.copyFrom(content)
            }
        })
        val r = response.writeFileResponse
        if (r.errorMessage.isNotBlank()) error(r.errorMessage)
    }

    suspend fun deleteFile(serverId: String, path: String, recursive: Boolean) {
        val nodeId = lookupNodeId(serverId)
        val reqId = Uuid.random()
            .toString()
        val response = controlService.sendAndAwait(nodeId, reqId, masterMessage {
            deleteFile = deleteFileRequest {
                requestId = reqId; this.serverId = serverId; this.path = path; this.recursive = recursive
            }
        })
        val r = response.deleteFileResponse
        if (r.errorMessage.isNotBlank()) error(r.errorMessage)
    }

    suspend fun makeDirectory(serverId: String, path: String) {
        val nodeId = lookupNodeId(serverId)
        val reqId = Uuid.random()
            .toString()
        val response = controlService.sendAndAwait(nodeId, reqId, masterMessage {
            makeDirectory = makeDirectoryRequest { requestId = reqId; this.serverId = serverId; this.path = path }
        })
        val r = response.makeDirectoryResponse
        if (r.errorMessage.isNotBlank()) error(r.errorMessage)
    }

    suspend fun moveFile(serverId: String, sourcePath: String, destinationPath: String) {
        val nodeId = lookupNodeId(serverId)
        val reqId = Uuid.random()
            .toString()
        val response = controlService.sendAndAwait(nodeId, reqId, masterMessage {
            moveFile = moveFileRequest {
                requestId = reqId; this.serverId = serverId
                this.sourcePath = sourcePath; this.destinationPath = destinationPath
            }
        })
        val r = response.moveFileResponse
        if (r.errorMessage.isNotBlank()) error(r.errorMessage)
    }

    suspend fun copyFile(serverId: String, sourcePath: String, destinationPath: String, recursive: Boolean) {
        val nodeId = lookupNodeId(serverId)
        val reqId = Uuid.random()
            .toString()
        val response = controlService.sendAndAwait(nodeId, reqId, masterMessage {
            copyFile = copyFileRequest {
                requestId = reqId; this.serverId = serverId
                this.sourcePath = sourcePath; this.destinationPath = destinationPath; this.recursive = recursive
            }
        })
        val r = response.copyFileResponse
        if (r.errorMessage.isNotBlank()) error(r.errorMessage)
    }

    // ── Bulk transfers ────────────────────────────────────────────────────────

    /**
     * Upload [content] to the agent at [path].
     * Returns the number of bytes written.
     */
    suspend fun uploadFile(serverId: String, path: String, content: ByteArray): Long {
        val nodeId = lookupNodeId(serverId)
        val transferId = Uuid.random()
            .toString()
        val reqId = Uuid.random()
            .toString()

        // Pre-fill the upload channel before signalling the agent so it's ready when agent connects.
        val uploadChannel = bulkService.registerUpload(transferId)
        val chunkSize = 65536
        content.toList()
            .chunked(chunkSize)
            .forEach { chunk ->
                uploadChannel.send(chunk.toByteArray())
            }
        uploadChannel.close()

        // Signal agent to open BulkDataService ReceiveFromMaster connection.
        val response = controlService.sendAndAwait(nodeId, reqId, masterMessage {
            uploadFile = uploadFileCommand {
                requestId = reqId; this.serverId = serverId; this.path = path; this.transferId = transferId
            }
        }, timeoutMs = 120_000)

        val r = response.uploadFileResponse
        if (!r.success) error(r.errorMessage.ifBlank { "Upload failed" })
        return r.sizeBytes
    }

    /**
     * Download the file at [path] from the agent.
     * Returns a Flow of byte arrays to be streamed to the HTTP client.
     * Throws before starting the stream if the file does not exist on the agent.
     */
    suspend fun downloadFile(serverId: String, path: String): Flow<ByteArray> {
        val nodeId = lookupNodeId(serverId)
        val transferId = Uuid.random()
            .toString()
        val reqId = Uuid.random()
            .toString()

        // Register the download channel before signalling the agent so BulkDataService is ready.
        val downloadFlow = bulkService.registerDownload(transferId)

        val response = runCatching {
            controlService.sendAndAwait(nodeId, reqId, masterMessage {
                downloadFile = downloadFileCommand {
                    requestId = reqId; this.serverId = serverId; this.path = path; this.transferId = transferId
                }
            })
        }.getOrElse { ex ->
            bulkService.cancelDownload(transferId)
            throw ex
        }

        val r = response.downloadFileResponse
        if (!r.success) {
            bulkService.cancelDownload(transferId)
            error(r.errorMessage.ifBlank { "File not found" })
        }

        return downloadFlow
    }

    suspend fun downloadBackup(serverId: String, backupId: String): Flow<ByteArray> {
        val nodeId = lookupNodeId(serverId)
        val transferId = Uuid.random()
            .toString()
        val reqId = Uuid.random()
            .toString()

        val downloadFlow = bulkService.registerDownload(transferId)

        val response = runCatching {
            controlService.sendAndAwait(nodeId, reqId, masterMessage {
                downloadBackup = downloadBackupCommand {
                    requestId = reqId
                    this.serverId = serverId
                    this.backupId = backupId
                    this.transferId = transferId
                }
            })
        }.getOrElse { ex ->
            bulkService.cancelDownload(transferId)
            throw ex
        }

        val r = response.downloadFileResponse
        if (!r.success) {
            bulkService.cancelDownload(transferId)
            error(r.errorMessage.ifBlank { "Backup file not found" })
        }

        return downloadFlow
    }
}
