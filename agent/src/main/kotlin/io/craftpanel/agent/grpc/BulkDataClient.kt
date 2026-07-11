package io.craftpanel.agent.grpc

import com.google.protobuf.ByteString
import io.craftpanel.proto.*
import io.grpc.ManagedChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.*

class BulkDataClient(channel: ManagedChannel) {

    private val log = LoggerFactory.getLogger(BulkDataClient::class.java)
    private val stub = BulkDataServiceGrpcKt.BulkDataServiceCoroutineStub(channel)

    suspend fun uploadToMaster(nodeKey: String, transferId: String, filePath: Path) {
        log.info("BulkData: uploading {} (transfer={})", filePath, transferId)
        if (!Files.exists(filePath)) {
            log.error("BulkData: file not found for upload: {}", filePath)
            return
        }

        val chunks = flow {
            val buffer = ByteArray(65536)
            var firstChunk = true
            var pending: ByteArray? = null
            Files.newInputStream(filePath)
                .use { stream ->
                    while (true) {
                        val read = stream.read(buffer)
                        if (read == -1) {
                            emit(
                                bulkChunk {
                                    if (firstChunk) {
                                        this.transferId = transferId
                                        this.nodeKey = nodeKey
                                        firstChunk = false
                                    }
                                    data = if (pending != null) ByteString.copyFrom(pending) else ByteString.EMPTY
                                    isLast = true
                                }
                            )
                            break
                        }
                        val current = buffer.copyOf(read)
                        pending?.let {
                            emit(
                                bulkChunk {
                                    if (firstChunk) {
                                        this.transferId = transferId
                                        this.nodeKey = nodeKey
                                        firstChunk = false
                                    }
                                    data = ByteString.copyFrom(it)
                                    isLast = false
                                }
                            )
                        }
                        pending = current
                    }
                }
        }.flowOn(Dispatchers.IO)

        runCatching {
            val ack = stub.streamToMaster(chunks)
            if (!ack.success) {
                log.error("BulkData: master rejected upload transfer={}: {}", transferId, ack.errorMessage)
            } else {
                log.info("BulkData: upload complete transfer={} size={}", transferId, ack.sizeBytes)
            }
        }.onFailure { log.error("BulkData: upload error transfer={}", transferId, it) }
    }

    suspend fun receiveFromMaster(nodeKey: String, transferId: String, destPath: Path) {
        log.info("BulkData: receiving file to {} (transfer={})", destPath, transferId)
        withContext(Dispatchers.IO) {
            Files.createDirectories(destPath.parent)
            val tempFile = Files.createTempFile(destPath.parent, "craftpanel-upload-", ".tmp")
            runCatching {
                val init = bulkTransferInit {
                    this.transferId = transferId
                    this.nodeKey = nodeKey
                }
                stub.receiveFromMaster(init)
                    .collect { chunk ->
                        if (chunk.errorMessage.isNotBlank()) {
                            error("Master signalled error: ${chunk.errorMessage}")
                        }
                        Files.newOutputStream(tempFile, StandardOpenOption.APPEND)
                            .use { out ->
                                out.write(chunk.data.toByteArray())
                            }
                        if (chunk.isLast) {
                            Files.move(tempFile, destPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                            log.info("BulkData: receive complete transfer={}", transferId)
                        }
                    }
            }.onFailure { ex ->
                runCatching { Files.deleteIfExists(tempFile) }
                log.error("BulkData: receive error transfer={}", transferId, ex)
            }
        }
    }
}
