package io.craftpanel.master.grpc

import com.craftpanel.agent.v1.BulkDataServiceGrpcKt
import com.craftpanel.agent.v1.BulkChunk
import com.craftpanel.agent.v1.BulkTransferInit
import com.craftpanel.agent.v1.BulkTransferAck
import com.craftpanel.agent.v1.bulkChunk
import com.craftpanel.agent.v1.bulkTransferAck
import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class BulkDataServiceImpl(private val controlService: ControlServiceImpl) :
    BulkDataServiceGrpcKt.BulkDataServiceCoroutineImplBase() {

    private val log = LoggerFactory.getLogger(BulkDataServiceImpl::class.java)

    // Download: agent → master. Master resolves these channels and pipes bytes to HTTP response.
    private val pendingDownloads = ConcurrentHashMap<String, Channel<ByteArray>>()

    // Upload: master → agent. Master pre-fills these channels; agent drains them.
    private val pendingUploads = ConcurrentHashMap<String, Channel<ByteArray>>()

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

    /**
     * Register a pending upload before sending UploadFileCommand to the agent.
     * The caller fills the returned channel with content chunks and closes it when done.
     */
    fun registerUpload(transferId: String): Channel<ByteArray> {
        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        pendingUploads[transferId] = channel
        return channel
    }

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
                    if (!controlService.verifyNodeKey(nodeKey)) {
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

        return bulkTransferAck { success = true; this.sizeBytes = sizeBytes }
    }

    // ── gRPC: master → agent (file upload from user perspective) ─────────────

    override fun receiveFromMaster(request: BulkTransferInit): Flow<BulkChunk> = flow {
        if (!controlService.verifyNodeKey(request.nodeKey)) {
            throw StatusException(Status.UNAUTHENTICATED.withDescription("Invalid node key"))
        }

        val channel = pendingUploads[request.transferId]
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Unknown transfer: ${request.transferId}"))

        log.info("BulkData: upload transfer {} starting", request.transferId)
        try {
            for (bytes in channel) {
                emit(bulkChunk { data = ByteString.copyFrom(bytes); isLast = false })
            }
            emit(bulkChunk { data = ByteString.EMPTY; isLast = true })
            log.info("BulkData: upload transfer {} complete", request.transferId)
        } finally {
            pendingUploads.remove(request.transferId)
        }
    }
}
