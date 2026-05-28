package io.craftpanel.agent.grpc

import com.craftpanel.agent.v1.*
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.Frame
import com.google.protobuf.ByteString
import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.ContainerManager
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import com.google.protobuf.timestamp

class DataServiceImpl(
    private val config: AgentConfig,
    private val containerManager: ContainerManager,
    private val docker: DockerClient,
) : DataServiceGrpcKt.DataServiceCoroutineImplBase() {

    private val log = LoggerFactory.getLogger(DataServiceImpl::class.java)

    // ── Console ──────────────────────────────────────────────────────────────

    override fun console(requests: Flow<ConsoleInput>): Flow<ConsoleOutput> = callbackFlow {
        val inputPipe = PipedOutputStream()
        val inputStream = PipedInputStream(inputPipe)
        var serverId = ""

        val callback = object : ResultCallback.Adapter<Frame>() {
            override fun onNext(frame: Frame) {
                frame.payload?.takeIf { it.isNotEmpty() }
                    ?.let { payload ->
                        trySend(consoleOutput {
                            this.serverId = serverId
                            data = ByteString.copyFrom(payload)
                        })
                    }
            }

            override fun onComplete() {
                close()
            }

            override fun onError(t: Throwable) {
                close(t)
            }
        }

        val inputJob = launch {
            try {
                requests.collect { input ->
                    if (serverId.isEmpty()) {
                        serverId = input.serverId
                        val containers = containerManager.listContainers()
                        val container = containers.find { it.serverId == serverId }
                        if (container == null) {
                            close(StatusException(Status.NOT_FOUND.withDescription("Server $serverId not found")))
                            return@collect
                        }
                        log.info("Attaching console to container ${container.containerName}")
                        docker.attachContainerCmd(container.containerName)
                            .withStdIn(inputStream)
                            .withStdOut(true)
                            .withStdErr(true)
                            .withFollowStream(true)
                            .withLogs(false)
                            .exec(callback)
                    }
                    if (input.data.size() > 0) {
                        runCatching {
                            inputPipe.write(input.data.toByteArray())
                            inputPipe.flush()
                        }
                    }
                }
            }
            finally {
                runCatching { inputPipe.close() }
            }
        }

        awaitClose {
            inputJob.cancel()
            runCatching { inputPipe.close() }
            runCatching { callback.close() }
        }
    }

    // ── File operations ───────────────────────────────────────────────────────

    override suspend fun listFiles(request: ListFilesRequest): ListFilesResponse {
        val root = serverDataRoot(request.serverId)
        val target = safeResolve(root, request.path.ifEmpty { "/" })
        if (!Files.exists(target)) throw StatusException(Status.NOT_FOUND.withDescription("Path not found"))
        if (!Files.isDirectory(target)) throw StatusException(Status.INVALID_ARGUMENT.withDescription("Path is not a directory"))

        val entries = Files.list(target)
            .use { stream ->
                stream.map { path ->
                    val attrs = runCatching { Files.readAttributes(path, BasicFileAttributes::class.java) }.getOrNull()
                    fileEntry {
                        name = path.fileName.toString()
                        isDirectory = Files.isDirectory(path)
                        sizeBytes = attrs?.size() ?: 0L
                        if (attrs != null) {
                            modifiedAt = timestamp {
                                seconds = attrs.lastModifiedTime()
                                    .toInstant().epochSecond
                            }
                        }
                        permissions = posixPermissions(path)
                    }
                }
                    .toList()
            }

        return listFilesResponse { this.entries.addAll(entries) }
    }

    override suspend fun readFile(request: ReadFileRequest): ReadFileResponse {
        val root = serverDataRoot(request.serverId)
        val target = safeResolve(root, request.path)
        if (!Files.exists(target)) throw StatusException(Status.NOT_FOUND.withDescription("File not found"))
        if (Files.isDirectory(target)) throw StatusException(Status.INVALID_ARGUMENT.withDescription("Path is a directory"))

        val bytes = Files.readAllBytes(target)
        val encoding = if (isTextContent(bytes)) "utf-8" else "binary"
        return readFileResponse {
            content = ByteString.copyFrom(bytes)
            this.encoding = encoding
        }
    }

    override suspend fun writeFile(request: WriteFileRequest): WriteFileResponse {
        val root = serverDataRoot(request.serverId)
        val target = safeResolve(root, request.path)
        Files.createDirectories(target.parent)
        Files.write(target, request.content.toByteArray())
        return writeFileResponse { success = true }
    }

    override suspend fun deleteFile(request: DeleteFileRequest): DeleteFileResponse {
        val root = serverDataRoot(request.serverId)
        val target = safeResolve(root, request.path)
        if (!Files.exists(target)) throw StatusException(Status.NOT_FOUND.withDescription("Path not found"))

        if (Files.isDirectory(target)) {
            if (!request.recursive) {
                val isEmpty = Files.list(target)
                    .use { it.findFirst().isEmpty }
                if (!isEmpty) throw StatusException(Status.FAILED_PRECONDITION.withDescription("Directory is not empty; use recursive=true"))
                Files.delete(target)
            }
            else {
                deleteRecursively(target)
            }
        }
        else {
            Files.delete(target)
        }
        return deleteFileResponse { success = true }
    }

    override suspend fun makeDirectory(request: MakeDirectoryRequest): MakeDirectoryResponse {
        val root = serverDataRoot(request.serverId)
        val target = safeResolve(root, request.path)
        Files.createDirectories(target)
        return makeDirectoryResponse { success = true }
    }

    override suspend fun moveFile(request: MoveFileRequest): MoveFileResponse {
        val root = serverDataRoot(request.serverId)
        val src = safeResolve(root, request.sourcePath)
        val dst = safeResolve(root, request.destinationPath)
        if (!Files.exists(src)) throw StatusException(Status.NOT_FOUND.withDescription("Source not found"))
        if (Files.exists(dst)) throw StatusException(Status.ALREADY_EXISTS.withDescription("Destination already exists"))
        Files.createDirectories(dst.parent)
        Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE)
        return moveFileResponse { success = true }
    }

    override suspend fun copyFile(request: CopyFileRequest): CopyFileResponse {
        val root = serverDataRoot(request.serverId)
        val src = safeResolve(root, request.sourcePath)
        val dst = safeResolve(root, request.destinationPath)
        if (!Files.exists(src)) throw StatusException(Status.NOT_FOUND.withDescription("Source not found"))
        if (Files.exists(dst)) throw StatusException(Status.ALREADY_EXISTS.withDescription("Destination already exists"))
        Files.createDirectories(dst.parent)
        if (request.recursive && Files.isDirectory(src)) {
            copyRecursively(src, dst)
        }
        else {
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
        }
        return copyFileResponse { success = true }
    }

    // ── Streaming file transfer ──────────────────────────────────────────────

    override suspend fun uploadFile(requests: Flow<UploadFileChunk>): UploadFileResponse {
        var serverId = ""
        var destPath = ""
        var tempFile: Path? = null
        var totalSize = 0L

        requests.collect { chunk ->
            if (serverId.isEmpty()) {
                serverId = chunk.serverId
                destPath = chunk.path
                tempFile = Files.createTempFile("craftpanel-upload-", ".tmp")
            }
            val file = tempFile ?: throw StatusException(Status.INTERNAL.withDescription("Upload stream error"))
            Files.newOutputStream(file, java.nio.file.StandardOpenOption.APPEND)
                .use { out ->
                    val bytes = chunk.data.toByteArray()
                    out.write(bytes)
                    totalSize += bytes.size
                }
            if (chunk.isLast) {
                val root = serverDataRoot(serverId)
                val dest = safeResolve(root, destPath)
                Files.createDirectories(dest.parent)
                Files.move(file, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                tempFile = null
            }
        }
        tempFile?.let { runCatching { Files.deleteIfExists(it) } }
        return uploadFileResponse { success = true; sizeBytes = totalSize }
    }

    override fun downloadFile(request: DownloadFileRequest): Flow<DownloadFileChunk> = flow {
        val root = serverDataRoot(request.serverId)
        val target = safeResolve(root, request.path)
        if (!Files.exists(target)) throw StatusException(Status.NOT_FOUND.withDescription("File not found"))
        if (Files.isDirectory(target)) throw StatusException(Status.INVALID_ARGUMENT.withDescription("Path is a directory"))

        val buffer = ByteArray(65536)
        Files.newInputStream(target)
            .use { stream ->
                var read: Int
                var first = true
                while (stream.read(buffer)
                        .also { read = it } != -1
                ) {
                    val chunk = buffer.copyOf(read)
                    val isLast = stream.available() == 0
                    emit(downloadFileChunk {
                        data = ByteString.copyFrom(chunk)
                        this.isLast = isLast
                    })
                    first = false
                }
                if (first) {
                    emit(downloadFileChunk { data = ByteString.EMPTY; isLast = true })
                }
            }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun serverDataRoot(serverId: String): Path =
        Paths.get(config.dataBasePath, "servers", serverId)
            .normalize()

    private fun safeResolve(root: Path, relativePath: String): Path {
        val clean = relativePath.trimStart('/')
        val resolved = root.resolve(clean)
            .normalize()
        if (!resolved.startsWith(root)) {
            throw StatusException(Status.PERMISSION_DENIED.withDescription("Path traversal detected"))
        }
        return resolved
    }

    private fun deleteRecursively(path: Path) {
        if (Files.isDirectory(path)) {
            Files.list(path)
                .use { stream -> stream.forEach { deleteRecursively(it) } }
        }
        Files.delete(path)
    }

    private fun copyRecursively(src: Path, dst: Path) {
        if (Files.isDirectory(src)) {
            Files.createDirectories(dst)
            Files.list(src)
                .use { stream ->
                    stream.forEach { child -> copyRecursively(child, dst.resolve(child.fileName)) }
                }
        }
        else {
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun posixPermissions(path: Path): String = runCatching {
        val perms = Files.getPosixFilePermissions(path)
        buildString {
            append(if (PosixFilePermission.OWNER_READ in perms) 'r' else '-')
            append(if (PosixFilePermission.OWNER_WRITE in perms) 'w' else '-')
            append(if (PosixFilePermission.OWNER_EXECUTE in perms) 'x' else '-')
            append(if (PosixFilePermission.GROUP_READ in perms) 'r' else '-')
            append(if (PosixFilePermission.GROUP_WRITE in perms) 'w' else '-')
            append(if (PosixFilePermission.GROUP_EXECUTE in perms) 'x' else '-')
            append(if (PosixFilePermission.OTHERS_READ in perms) 'r' else '-')
            append(if (PosixFilePermission.OTHERS_WRITE in perms) 'w' else '-')
            append(if (PosixFilePermission.OTHERS_EXECUTE in perms) 'x' else '-')
        }
    }.getOrElse { "rwxr-xr-x" }

    private fun isTextContent(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        val sample = bytes.take(8192)
        val nullCount = sample.count { it == 0.toByte() }
        return nullCount == 0
    }
}
