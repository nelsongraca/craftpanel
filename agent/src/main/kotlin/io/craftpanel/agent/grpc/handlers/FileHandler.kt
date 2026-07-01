package io.craftpanel.agent.grpc.handlers

import com.google.protobuf.ByteString
import com.google.protobuf.timestamp
import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.grpc.AgentOutbound
import io.craftpanel.agent.grpc.BulkDataClient
import io.craftpanel.proto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission

class FileHandler(
    private val config: AgentConfig,
    private val nodeKey: String,
) {

    private val log = LoggerFactory.getLogger(FileHandler::class.java)

    /** Fills the standard failure fields from a Result onto a proto response builder. */
    // ponytail: private to FileHandler until a 2nd caller appears
    private inline fun <T> Result<T>.fillFailure(
        setMessage: (String) -> Unit,
        setCode: (ErrorCode) -> Unit,
    ) = onFailure {
        setMessage(it.message ?: "Unknown error")
        setCode(classifyFileError(it))
    }

    suspend fun handleListFiles(cmd: ListFilesRequest, out: AgentOutbound) {
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val root = serverDataRoot(cmd.serverId)
                log.debug("listFiles serverId={} requestedPath={} root={} rootExists={}", cmd.serverId, cmd.path, root, Files.exists(root))
                val target = safeResolve(root, cmd.path.ifEmpty { "/" })
                log.debug("listFiles target={} exists={} isDir={}", target, Files.exists(target), Files.isDirectory(target))
                if (!Files.isDirectory(target)) {
                    if (target == root) {
                        Files.createDirectories(root)
                        return@withContext emptyList()
                    }
                    error("Path not found")
                }
                Files.list(target)
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
            }
        }
        out.send {
            listFilesResponse = listFilesResponse {
                requestId = cmd.requestId
                result.onSuccess { entries.addAll(it) }
                result.fillFailure({ errorMessage = it }, { errorCode = it })
                result.onFailure { log.debug(errorMessage, it) }
            }
        }
    }

    suspend fun handleReadFile(cmd: ReadFileRequest, out: AgentOutbound) {
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val root = serverDataRoot(cmd.serverId)
                val target = safeResolve(root, cmd.path)
                if (!Files.exists(target)) error("File not found")
                if (Files.isDirectory(target)) error("Path is a directory")
                val bytes = Files.readAllBytes(target)
                Pair(bytes, if (isTextContent(bytes)) "utf-8" else "binary")
            }
        }
        out.send {
            readFileResponse = readFileResponse {
                requestId = cmd.requestId
                result.onSuccess { (bytes, enc) ->
                    content = ByteString.copyFrom(bytes)
                    encoding = enc
                }
                result.fillFailure({ errorMessage = it }, { errorCode = it })
            }
        }
    }

    suspend fun handleWriteFile(cmd: WriteFileRequest, out: AgentOutbound) {
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val root = serverDataRoot(cmd.serverId)
                val target = safeResolve(root, cmd.path)
                Files.createDirectories(target.parent)
                Files.write(target, cmd.content.toByteArray())
            }
        }
        out.send {
            writeFileResponse = writeFileResponse {
                requestId = cmd.requestId
                success = result.isSuccess
                result.fillFailure({ errorMessage = it }, { errorCode = it })
            }
        }
    }

    suspend fun handleDeleteFile(cmd: DeleteFileRequest, out: AgentOutbound) {
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val root = serverDataRoot(cmd.serverId)
                val target = safeResolve(root, cmd.path)
                if (!Files.exists(target)) error("Path not found")
                if (Files.isDirectory(target)) {
                    if (!cmd.recursive) {
                        val isEmpty = Files.list(target)
                            .use { it.findFirst().isEmpty }
                        if (!isEmpty) error("Directory is not empty; use recursive=true")
                        Files.delete(target)
                    }
                    else {
                        deleteRecursively(target)
                    }
                }
                else {
                    Files.delete(target)
                }
            }
        }
        out.send {
            deleteFileResponse = deleteFileResponse {
                requestId = cmd.requestId
                success = result.isSuccess
                result.fillFailure({ errorMessage = it }, { errorCode = it })
            }
        }
    }

    suspend fun handleMakeDirectory(cmd: MakeDirectoryRequest, out: AgentOutbound) {
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val root = serverDataRoot(cmd.serverId)
                val target = safeResolve(root, cmd.path)
                Files.createDirectories(target)
            }
        }
        out.send {
            makeDirectoryResponse = makeDirectoryResponse {
                requestId = cmd.requestId
                success = result.isSuccess
                result.fillFailure({ errorMessage = it }, { errorCode = it })
            }
        }
    }

    suspend fun handleMoveFile(cmd: MoveFileRequest, out: AgentOutbound) {
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val root = serverDataRoot(cmd.serverId)
                val src = safeResolve(root, cmd.sourcePath)
                val dst = safeResolve(root, cmd.destinationPath)
                if (!Files.exists(src)) error("Source not found")
                if (Files.exists(dst)) error("Destination already exists")
                Files.createDirectories(dst.parent)
                Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE)
            }
        }
        out.send {
            moveFileResponse = moveFileResponse {
                requestId = cmd.requestId
                success = result.isSuccess
                result.fillFailure({ errorMessage = it }, { errorCode = it })
            }
        }
    }

    suspend fun handleCopyFile(cmd: CopyFileRequest, out: AgentOutbound) {
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val root = serverDataRoot(cmd.serverId)
                val src = safeResolve(root, cmd.sourcePath)
                val dst = safeResolve(root, cmd.destinationPath)
                if (!Files.exists(src)) error("Source not found")
                if (Files.exists(dst)) error("Destination already exists")
                Files.createDirectories(dst.parent)
                if (Files.isDirectory(src)) copyRecursively(src, dst)
                else Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        out.send {
            copyFileResponse = copyFileResponse {
                requestId = cmd.requestId
                success = result.isSuccess
                result.fillFailure({ errorMessage = it }, { errorCode = it })
            }
        }
    }

    suspend fun handleDownloadFile(cmd: DownloadFileCommand, bulkClient: BulkDataClient, out: AgentOutbound) {
        val fileResult = runCatching {
            withContext(Dispatchers.IO) {
                val root = serverDataRoot(cmd.serverId)
                val filePath = safeResolve(root, cmd.path)
                if (!Files.exists(filePath)) error("File not found: ${cmd.path}")
                filePath
            }
        }
        out.send {
            downloadFileResponse = downloadFileResponse {
                requestId = cmd.requestId
                success = fileResult.isSuccess
                fileResult.fillFailure({ errorMessage = it }, { errorCode = it })
            }
        }
        if (fileResult.isSuccess) {
            runCatching { bulkClient.uploadToMaster(nodeKey, cmd.transferId, fileResult.getOrThrow()) }
                .onFailure { log.error("Download transfer ${cmd.transferId}: bulk upload failed", it) }
        }
    }

    suspend fun handleUploadFile(cmd: UploadFileCommand, bulkClient: BulkDataClient, out: AgentOutbound) {
        val result = runCatching {
            val root = serverDataRoot(cmd.serverId)
            val destPath = safeResolve(root, cmd.path)
            bulkClient.receiveFromMaster(nodeKey, cmd.transferId, destPath)
        }
        out.send {
            uploadFileResponse = uploadFileResponse {
                requestId = cmd.requestId
                success = result.isSuccess
                result.fillFailure({ errorMessage = it }, { errorCode = it })
            }
        }
    }

    suspend fun handleDownloadBackup(cmd: DownloadBackupCommand, bulkClient: BulkDataClient, out: AgentOutbound) {
        val fileResult = runCatching {
            withContext(Dispatchers.IO) {
                if (!cmd.backupId.matches(Regex("^[0-9a-fA-F-]{36}$"))) error("Invalid backup id")
                val backupsRoot = Paths.get(config.dataBasePath, "backups")
                    .normalize()
                val filePath = backupsRoot.resolve("${cmd.backupId}.tar.gz")
                    .normalize()
                if (!filePath.startsWith(backupsRoot)) error("Invalid backup id")
                if (!Files.exists(filePath)) error("Backup file not found: ${cmd.backupId}")
                filePath
            }
        }
        out.send {
            downloadFileResponse = downloadFileResponse {
                requestId = cmd.requestId
                success = fileResult.isSuccess
                fileResult.fillFailure({ errorMessage = it }, { errorCode = it })
            }
        }
        if (fileResult.isSuccess) {
            runCatching { bulkClient.uploadToMaster(nodeKey, cmd.transferId, fileResult.getOrThrow()) }
                .onFailure { log.error("Download transfer ${cmd.transferId}: bulk upload failed", it) }
        }
    }

    // ─── Path helpers ─────────────────────────────────────────────────────────

    private fun serverDataRoot(serverId: String): Path =
        Paths.get(config.dataBasePath, "servers", serverId)
            .normalize()

    private fun safeResolve(root: Path, relativePath: String): Path {
        log.debug("safeResolve root={} relativePath={}", root, relativePath)
        val clean = relativePath.trimStart('/')
        val resolved = root.resolve(clean)
            .normalize()
        log.debug("safeResolve resolved={} startsWithRoot={}", resolved, resolved.startsWith(root))
        if (!resolved.startsWith(root)) error("Path traversal detected")
        return resolved
    }

    private fun deleteRecursively(path: Path) {
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
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

internal fun classifyFileError(ex: Throwable): ErrorCode = when (ex) {
    is NoSuchFileException        -> ErrorCode.NOT_FOUND
    is FileAlreadyExistsException -> ErrorCode.ALREADY_EXISTS
    is DirectoryNotEmptyException -> ErrorCode.CONFLICT
    is AccessDeniedException      -> ErrorCode.PERMISSION_DENIED
    is IOException                -> ErrorCode.INTERNAL
    else                          -> ErrorCode.INTERNAL
}
