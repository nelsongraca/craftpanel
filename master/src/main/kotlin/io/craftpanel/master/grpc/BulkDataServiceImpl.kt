package io.craftpanel.master.grpc

import com.google.protobuf.ByteString
import io.craftpanel.master.service.NodeRegistrationService
import io.craftpanel.proto.*
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * One frame of a master → agent upload transfer. [FileStart] and [FileEnd] delimit
 * file entries; [Data] carries the bytes in between. `BulkDataServiceImpl` translates
 * these into `BulkChunk.file_path` / `file_end` markers.
 */
sealed interface UploadFrame {
    data class FileStart(val path: String) : UploadFrame

    data class Data(val bytes: ByteArray) : UploadFrame

    data object FileEnd : UploadFrame
}

class BulkDataServiceImpl(private val nodeRegistrationService: NodeRegistrationService) : BulkDataServiceGrpcKt.BulkDataServiceCoroutineImplBase() {

    private val log = LoggerFactory.getLogger(BulkDataServiceImpl::class.java)

    // Download: agent → master. Master resolves these channels and pipes bytes to HTTP response.
    private val pendingDownloads = ConcurrentHashMap<String, Channel<ByteArray>>()

    // Upload: master → agent. Master pumps frames into these bounded channels; agent drains them.
    private val pendingUploads = ConcurrentHashMap<String, Channel<UploadFrame>>()

    /**
     * Register a pending download before sending DownloadFileCommand to the agent.
     * Returns a Flow of byte arrays that the HTTP handler can stream to the client.
     */
    fun registerDownload(transferId: String): Flow<ByteArray> {
        val channel = Channel<ByteArray>(Channel.BUFFERED)
        pendingDownloads[transferId] = channel
        return flow {
            try {
                for (bytes in channel) {
                    emit(bytes)
                }
            } finally {
                pendingDownloads.remove(transferId)
            }
        }
    }

    fun cancelDownload(transferId: String) {
        pendingDownloads.remove(transferId)
            ?.close()
    }

    /**
     * Register a pending upload before sending UploadFilesCommand to the agent.
     * The caller pumps frames into the returned bounded channel and closes it when done.
     *
     * Bounded on purpose: a slow (or stalled) agent applies backpressure to the HTTP
     * body reader instead of letting master buffer the whole upload in memory.
     */
    fun registerUpload(transferId: String): Channel<UploadFrame> {
        val channel = Channel<UploadFrame>(UPLOAD_CHANNEL_CAPACITY)
        pendingUploads[transferId] = channel
        return channel
    }

    /** Cancel a pending upload and unblock a pump waiting on the bounded channel. */
    fun cancelUpload(transferId: String) {
        pendingUploads.remove(transferId)
            ?.close()
    }

    /** Test seam: the pending upload channel for [transferId], if any. */
    internal fun pendingUpload(transferId: String): Channel<UploadFrame>? = pendingUploads[transferId]

    // ── gRPC: agent → master (file download from user perspective) ────────────

    override suspend fun streamToMaster(requests: Flow<BulkChunk>): BulkTransferAck {
        var transferId = ""
        var authenticated = false
        var sizeBytes = 0L
        var downloadChannel: Channel<ByteArray>? = null

        runCatching {
            requests.collect { chunk ->
                if (transferId.isEmpty()) {
                    transferId = chunk.transferId
                    val nodeKey = chunk.nodeKey
                    if (!nodeRegistrationService.isActive(nodeKey)) {
                        throw StatusException(Status.UNAUTHENTICATED.withDescription("Invalid node key"))
                    }
                    authenticated = true
                    downloadChannel = pendingDownloads[transferId]
                        ?: throw StatusException(Status.NOT_FOUND.withDescription("Unknown transfer: $transferId"))
                }

                if (!authenticated) return@collect

                if (chunk.errorMessage.isNotBlank()) {
                    log.error("BulkData: agent signalled error on transfer {}: {}", transferId, chunk.errorMessage)
                    downloadChannel?.close(Exception(chunk.errorMessage))
                    pendingDownloads.remove(transferId)
                    return@collect
                }

                if (chunk.data.size() > 0) {
                    downloadChannel?.send(chunk.data.toByteArray())
                    sizeBytes += chunk.data.size()
                }

                if (chunk.isLast) {
                    downloadChannel?.close()
                    pendingDownloads.remove(transferId)
                    log.info("BulkData: download transfer {} complete, {} bytes", transferId, sizeBytes)
                }
            }
        }.onFailure { ex ->
            downloadChannel?.close(ex)
            pendingDownloads.remove(transferId)
            log.error("BulkData: download transfer {} error", transferId, ex)
        }

        return bulkTransferAck {
            success = true
            this.sizeBytes = sizeBytes
        }
    }

    // ── gRPC: master → agent (file upload from user perspective) ─────────────

    override fun receiveFromMaster(request: BulkTransferInit): Flow<BulkChunk> = flow {
        if (!nodeRegistrationService.isActive(request.nodeKey)) {
            throw StatusException(Status.UNAUTHENTICATED.withDescription("Invalid node key"))
        }

        val channel = pendingUploads[request.transferId]
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Unknown transfer: ${request.transferId}"))

        log.info("BulkData: upload transfer {} starting", request.transferId)
        try {
            for (frame in channel) {
                emit(
                    when (frame) {
                        is UploadFrame.FileStart -> bulkChunk {
                            filePath = frame.path
                            data = ByteString.EMPTY
                            isLast = false
                        }

                        is UploadFrame.Data -> bulkChunk {
                            data = ByteString.copyFrom(frame.bytes)
                            isLast = false
                        }

                        UploadFrame.FileEnd -> bulkChunk {
                            data = ByteString.EMPTY
                            fileEnd = true
                            isLast = false
                        }
                    }
                )
            }
            emit(
                bulkChunk {
                    data = ByteString.EMPTY
                    isLast = true
                }
            )
            log.info("BulkData: upload transfer {} complete", request.transferId)
        } finally {
            // Closing is load-bearing: a failed/aborted transfer must unblock the pump
            // waiting on the bounded channel instead of leaking it forever.
            pendingUploads.remove(request.transferId)
                ?.close()
        }
    }

    private companion object {
        /** Frames buffered ahead of the agent (64 KiB each → ~1 MiB of in-flight data). */
        const val UPLOAD_CHANNEL_CAPACITY = 16
    }
}
