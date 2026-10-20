package io.craftpanel.agent.grpc

import com.google.protobuf.ByteString
import io.craftpanel.agent.grpc.handlers.classifyFileError
import io.craftpanel.agent.grpc.handlers.safeResolve
import io.craftpanel.proto.*
import io.grpc.ManagedChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.nio.file.*

/** Outcome of one file received in a batch upload transfer. */
data class FileUploadResult(val path: String, val success: Boolean, val sizeBytes: Long, val errorMessage: String = "", val errorCode: ErrorCode = ErrorCode.ERROR_CODE_UNSPECIFIED)

/** Outcome of a batch upload transfer; [transferError] is set when the stream itself failed. */
data class ReceiveFilesResult(val files: List<FileUploadResult>, val transferError: String? = null)

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

    /**
     * Receives one or more files framed by [BulkChunk.filePath] / [BulkChunk.fileEnd] and
     * writes each atomically under [root].
     *
     * Per-file problems (invalid path, permission, disk error) are reported in the result
     * and the transfer keeps draining, so one bad entry never abandons the rest of the
     * batch. Only cancellation throws.
     */
    suspend fun receiveFilesFromMaster(nodeKey: String, transferId: String, root: Path): ReceiveFilesResult = withContext(Dispatchers.IO) {
        log.info("BulkData: receiving files into {} (transfer={})", root, transferId)
        val results = mutableListOf<FileUploadResult>()
        var current: Entry? = null
        var transferError: String? = null

        fun finishCurrent(abortReason: String? = null) {
            current?.let { results += it.finish(abortReason) }
            current = null
        }

        val init = bulkTransferInit {
            this.transferId = transferId
            this.nodeKey = nodeKey
        }

        try {
            stub.receiveFromMaster(init)
                .collect { chunk ->
                    if (chunk.errorMessage.isNotBlank()) {
                        error("Master signalled error: ${chunk.errorMessage}")
                    }
                    if (chunk.filePath.isNotEmpty()) {
                        // Defensive: a new entry before file_end means the previous one was truncated.
                        if (current != null) finishCurrent("Stream ended before file_end")
                        current = openEntry(root, chunk.filePath)
                    }
                    current?.write(chunk.data.toByteArray())
                    if (chunk.fileEnd) finishCurrent()
                    if (chunk.isLast) finishCurrent("Stream ended before file_end")
                }
            // Stream ended without an is_last marker: treat whatever is open as truncated.
            finishCurrent("Stream ended before file_end")
        } catch (e: CancellationException) {
            finishCurrent("Upload cancelled")
            throw e
        } catch (e: Throwable) {
            transferError = e.message ?: "Upload transfer failed"
            finishCurrent("Upload transfer failed")
            log.error("BulkData: receive error transfer={}", transferId, e)
        }

        log.info(
            "BulkData: receive complete transfer={} files={} transferError={}",
            transferId,
            results.size,
            transferError
        )
        ReceiveFilesResult(results, transferError)
    }

    private fun openEntry(root: Path, relativePath: String): Entry = try {
        val target = safeResolve(root, relativePath)
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, "craftpanel-upload-", ".tmp")
        val out = Files.newOutputStream(temp)
        Entry(relativePath, target, temp, out, null)
    } catch (e: Throwable) {
        Entry(relativePath, null, null, null, e)
    }

    /**
     * One in-flight file. Bytes are written to a temp file in the destination directory and
     * moved into place on [finish], so a failed transfer never leaves a partial target file.
     */
    private class Entry(private val path: String, private val target: Path?, private val temp: Path?, private val out: OutputStream?, private var failure: Throwable?) {

        private var sizeBytes = 0L

        fun write(bytes: ByteArray) {
            if (failure != null || out == null || bytes.isEmpty()) return
            try {
                out.write(bytes)
                sizeBytes += bytes.size
            } catch (e: Throwable) {
                failure = e
            }
        }

        fun finish(abortReason: String?): FileUploadResult {
            runCatching { out?.flush() }
            runCatching { out?.close() }

            if (failure == null && abortReason != null) {
                failure = IllegalStateException(abortReason)
            }

            val tempFile = temp
            val destination = target
            if (failure == null && tempFile != null && destination != null) {
                val move = runCatching {
                    Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                }.recoverCatching {
                    Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING)
                }
                if (move.isFailure) failure = move.exceptionOrNull()
            }

            val cause = failure
            if (cause != null) {
                temp?.let { runCatching { Files.deleteIfExists(it) } }
            }

            return FileUploadResult(
                path = path,
                success = cause == null,
                sizeBytes = if (cause == null) sizeBytes else 0,
                errorMessage = cause?.message ?: "",
                errorCode = cause?.let { classifyFileError(it) } ?: ErrorCode.ERROR_CODE_UNSPECIFIED
            )
        }
    }
}
