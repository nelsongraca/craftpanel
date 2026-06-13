package io.craftpanel.agent.grpc

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.Frame
import com.google.protobuf.ByteString
import com.google.protobuf.timestamp
import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.docker.MetricsCollector
import io.craftpanel.proto.*
import io.grpc.ManagedChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

class ControlStreamHandler(
    private val identity: NodeIdentity,
    private val config: AgentConfig,
    private val containerManager: ContainerManager,
    private val metricsCollector: MetricsCollector,
    private val docker: DockerClient,
    private val container: io.craftpanel.agent.grpc.handlers.ContainerHandler,
) {

    private val log = LoggerFactory.getLogger(ControlStreamHandler::class.java)

    // Active console sessions: request_id → (job, inputPipe, detached)
    private val consoleSessions = ConcurrentHashMap<String, Triple<Job, PipedOutputStream, AtomicBoolean>>()

    suspend fun run(channel: ManagedChannel): Unit = coroutineScope {
        val stub = ControlServiceGrpcKt.ControlServiceCoroutineStub(channel)
        val bulkClient = BulkDataClient(channel)
        val outboundChannel = Channel<AgentMessage>(capacity = 64)
        val out = AgentOutbound(outboundChannel, identity.nodeId)

        val stream = stub.control(outboundChannel.receiveAsFlow())

        // Send NodeStateSnapshot as the first message
        val snapshot = buildStateSnapshot()
        outboundChannel.send(agentMessage {
            nodeId = identity.nodeId
            nodeState = snapshot
        })
        log.info("Sent NodeStateSnapshot with ${snapshot.containersCount} containers")

        // Periodic metrics loop
        val metricsInterval = config.metricsPollIntervalSeconds.toLong().seconds
        launch {
            while (true) {
                delay(metricsInterval)
                val metrics = metricsCollector.collect()
                out.send { nodeMetrics = metrics }

                containerManager.listRunningContainerIds()
                    .forEach { (serverId, containerId) ->
                        metricsCollector.collectContainerMetrics(serverId, containerId)
                            ?.let { cm -> out.send { containerMetrics = cm } }
                        metricsCollector.collectPlayerCount(serverId, containerId)
                            ?.let { pu -> out.send { playerUpdate = pu } }
                    }
            }
        }

        // Process inbound commands from master
        stream.collect { msg ->
            log.debug("Received master command: {}", msg.payloadCase)
            when {
                msg.hasCreateContainer()     -> dispatch { container.handleCreate(msg.createContainer, out) }
                msg.hasStartContainer()      -> dispatch { container.handleStart(msg.startContainer, out) }
                msg.hasStopContainer()       -> launch { dispatch { container.handleStop(msg.stopContainer, out) } }
                msg.hasRestartContainer()    -> launch { dispatch { container.handleRestart(msg.restartContainer, out) } }
                msg.hasRemoveContainer()     -> dispatch { container.handleRemove(msg.removeContainer) }
                msg.hasPullImage()           -> dispatch { container.handlePullImage(msg.pullImage) }
                msg.hasShutdown()            -> dispatch { container.handleShutdown(msg.shutdown, out) }
                msg.hasTriggerBackup()       -> launch { dispatch { handleTriggerBackup(msg.triggerBackup, out) } }
                msg.hasDeleteBackup()        -> launch { dispatch { handleDeleteBackup(msg.deleteBackup) } }
                msg.hasPrepareRsyncReceive() -> launch { dispatch { handlePrepareRsyncReceive(msg.prepareRsyncReceive, out) } }
                msg.hasStartRsync()          -> launch { dispatch { handleStartRsync(msg.startRsync, out) } }
                msg.hasSendRcon()            -> launch { dispatch { handleSendRcon(msg.sendRcon) } }
                // Console
                msg.hasConsoleAttach()       -> launch { dispatch { handleConsoleAttach(msg.consoleAttach, out) } }
                msg.hasConsoleInput()        -> dispatch { handleConsoleInput(msg.consoleInput) }
                msg.hasConsoleDetach()       -> dispatch { handleConsoleDetach(msg.consoleDetach) }
                // File ops (unary) — each in its own coroutine so it does not block the stream
                msg.hasListFiles()           -> launch { dispatch { handleListFiles(msg.listFiles, out) } }
                msg.hasReadFile()            -> launch { dispatch { handleReadFile(msg.readFile, out) } }
                msg.hasWriteFile()           -> launch { dispatch { handleWriteFile(msg.writeFile, out) } }
                msg.hasDeleteFile()          -> launch { dispatch { handleDeleteFile(msg.deleteFile, out) } }
                msg.hasMakeDirectory()       -> launch { dispatch { handleMakeDirectory(msg.makeDirectory, out) } }
                msg.hasMoveFile()            -> launch { dispatch { handleMoveFile(msg.moveFile, out) } }
                msg.hasCopyFile()            -> launch { dispatch { handleCopyFile(msg.copyFile, out) } }
                // Bulk transfers — agent dials master's BulkDataService
                msg.hasDownloadFile()        -> launch { dispatch { handleDownloadFile(msg.downloadFile, bulkClient, out) } }
                msg.hasUploadFile()          -> launch { dispatch { handleUploadFile(msg.uploadFile, bulkClient, out) } }
                msg.hasDownloadBackup()      -> launch { dispatch { handleDownloadBackup(msg.downloadBackup, bulkClient, out) } }
                else                         -> log.warn("Unhandled master message: ${msg.payloadCase}")
            }
        }
    }

    private suspend fun dispatch(block: suspend () -> Unit) {
        runCatching { block() }
            .onFailure { if (it is CancellationException) throw it else log.error("Unexpected handler failure", it) }
    }

    internal fun buildStateSnapshot(): NodeStateSnapshot {
        val containers = containerManager.listContainers()
        return nodeStateSnapshot {
            this.containers.addAll(containers)
            recordedAt = nowTimestamp()
        }
    }

    // ─── Backup ───────────────────────────────────────────────────────────────

    internal suspend fun handleTriggerBackup(cmd: TriggerBackupCommand, out: AgentOutbound) {
        val sourceDir = "${config.dataBasePath}/servers/${cmd.serverId}"
        val destPath = "${config.dataBasePath}/backups/${cmd.backupId}.tar.gz"
        log.info("Backup ${cmd.backupId}: starting for server ${cmd.serverId} → $destPath")

        out.send {
            backupProgress = backupProgressUpdate {
                backupId = cmd.backupId
                serverId = cmd.serverId
                percentComplete = 0
            }
        }

        runCatching {
            withContext(Dispatchers.IO) {
                val destFile = File(destPath)
                destFile.parentFile?.mkdirs()

                val process = ProcessBuilder("tar", "-czf", destPath, "-C", sourceDir, ".")
                    .redirectErrorStream(true)
                    .start()

                out.send {
                    backupProgress = backupProgressUpdate {
                        backupId = cmd.backupId
                        serverId = cmd.serverId
                        percentComplete = 50
                    }
                }

                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    val output = process.inputStream.bufferedReader()
                        .readText()
                    error("tar exited with $exitCode: $output")
                }

                File(destPath).length()
            }
        }.onSuccess { sizeBytes ->
            log.info("Backup ${cmd.backupId}: completed, size=$sizeBytes bytes")
            out.send {
                backupComplete = backupCompleteUpdate {
                    backupId = cmd.backupId
                    serverId = cmd.serverId
                    success = true
                    filePath = destPath
                    this.sizeBytes = sizeBytes
                    completedAt = nowTimestamp()
                }
            }
        }
            .onFailure { ex ->
                log.error("Backup ${cmd.backupId}: failed", ex)
                out.send {
                    backupComplete = backupCompleteUpdate {
                        backupId = cmd.backupId
                        serverId = cmd.serverId
                        success = false
                        errorMessage = ex.message ?: "Unknown error"
                        completedAt = nowTimestamp()
                    }
                }
            }
    }

    internal suspend fun handleDeleteBackup(cmd: DeleteBackupCommand) {
        log.info("Deleting backup file ${cmd.filePath}")
        runCatching {
            withContext(Dispatchers.IO) { File(cmd.filePath).delete() }
        }.onFailure { log.error("Failed to delete backup file ${cmd.filePath}", it) }
    }

    // ─── Migration ────────────────────────────────────────────────────────────

    internal suspend fun handlePrepareRsyncReceive(
        cmd: PrepareRsyncReceiveCommand,
        out: AgentOutbound,
    ) {
        val destPath = "${config.dataBasePath}/servers/${cmd.serverId}"
        log.info("Migration ${cmd.migrationId}: preparing rsync receiver on port ${cmd.port} → $destPath")
        val rsyncImage = cmd.rsyncImage.ifEmpty { "alpine:latest" }
        withContext(Dispatchers.IO) { containerManager.pullImage(rsyncImage) }
        runCatching {
            withContext(Dispatchers.IO) {
                val password = generateRsyncPassword()
                containerManager.startRsyncdContainer(
                    migrationId = cmd.migrationId,
                    port = cmd.port,
                    destPath = destPath,
                    password = password,
                    rsyncImage = rsyncImage,
                )
                password
            }
        }.onSuccess { password ->
            out.send {
                rsyncReady = rsyncReadyUpdate {
                    migrationId = cmd.migrationId
                    rsyncPassword = password
                }
            }
            log.info("Migration ${cmd.migrationId}: rsyncd ready")
        }
            .onFailure { ex ->
                log.error("Migration ${cmd.migrationId}: failed to start rsyncd", ex)
            }
    }

    internal suspend fun handleStartRsync(
        cmd: StartRsyncCommand,
        out: AgentOutbound,
    ) {
        val sourcePath = "${config.dataBasePath}/servers/${cmd.serverId}"
        log.info("Migration ${cmd.migrationId}: starting rsync transfer (final=${cmd.isFinalPass})")
        val rsyncImage = cmd.rsyncImage.ifEmpty { "alpine:latest" }
        withContext(Dispatchers.IO) { containerManager.pullImage(rsyncImage) }
        runCatching {
            withContext(Dispatchers.IO) {
                containerManager.runRsyncTransfer(
                    migrationId = cmd.migrationId,
                    sourcePath = sourcePath,
                    destIp = cmd.destinationIp,
                    destPort = cmd.destinationPort,
                    password = cmd.rsyncPassword,
                    isFinalPass = cmd.isFinalPass,
                    rsyncImage = rsyncImage,
                    onProgress = { bytes, total, pct, phase ->
                        out.trySend {
                            rsyncProgress = rsyncProgressUpdate {
                                migrationId = cmd.migrationId
                                isFinalPass = cmd.isFinalPass
                                bytesTransferred = bytes
                                totalBytes = total
                                percentComplete = pct
                                this.phase = phase
                                recordedAt = nowTimestamp()
                            }
                        }
                    },
                )
            }
        }.onSuccess { success ->
            out.send {
                rsyncComplete = rsyncCompleteUpdate {
                    migrationId = cmd.migrationId
                    isFinalPass = cmd.isFinalPass
                    this.success = success
                    if (!success) errorMessage = "rsync exited with non-zero code"
                    completedAt = nowTimestamp()
                }
            }
            log.info("Migration ${cmd.migrationId}: rsync transfer complete (success=$success)")
        }
            .onFailure { ex ->
                log.error("Migration ${cmd.migrationId}: rsync transfer error", ex)
                out.send {
                    rsyncComplete = rsyncCompleteUpdate {
                        migrationId = cmd.migrationId
                        isFinalPass = cmd.isFinalPass
                        success = false
                        errorMessage = ex.message ?: "Unknown error"
                        completedAt = nowTimestamp()
                    }
                }
            }
    }

    internal fun handleSendRcon(cmd: SendRconCommand) {
        log.debug("RCON exec on server ${cmd.serverId}: ${cmd.command}")
        containerManager.execRconCommand(cmd.serverId, cmd.command)
    }

    // ─── Console ──────────────────────────────────────────────────────────────

    internal suspend fun handleConsoleAttach(cmd: ConsoleAttach, out: AgentOutbound) {
        val reqId = cmd.requestId
        if (consoleSessions.containsKey(reqId)) {
            log.warn("Console attach for already-active session $reqId — ignoring")
            return
        }

        val containers = containerManager.listContainers()
        val container = containers.find { it.serverId == cmd.serverId }
        if (container == null) {
            out.send { consoleOutput = consoleOutput { requestId = reqId; closed = true } }
            log.warn("Console attach: server ${cmd.serverId} not found")
            return
        }

        if (container.runState != ContainerState.RunState.RUNNING) {
            log.warn("Console attach: server ${cmd.serverId} container is ${container.runState}, not RUNNING")
            out.send { consoleOutput = consoleOutput { requestId = reqId; closed = true } }
            return
        }

        val inputPipe = PipedOutputStream()
        val detached = AtomicBoolean(false)

        @Suppress("BlockingMethodInNonBlockingContext")
        val inputStream = PipedInputStream(inputPipe)

        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                val callback = object : ResultCallback.Adapter<Frame>() {
                    override fun onNext(frame: Frame) {
                        frame.payload?.takeIf { it.isNotEmpty() }
                            ?.let { payload ->
                                out.tryConsoleOutput(reqId) { data = ByteString.copyFrom(payload) }
                            }
                    }

                    override fun onComplete() {
                        out.tryConsoleOutput(reqId) { closed = true }
                        if (!detached.get()) {
                            out.tryServerStatus(cmd.serverId, ServerStatusUpdate.ServerStatus.STOPPED)
                        }
                        consoleSessions.remove(reqId)
                    }

                    override fun onError(t: Throwable) {
                        log.warn("Console attach error (session=$reqId): ${t.message}")
                        out.tryConsoleOutput(reqId) { closed = true }
                        consoleSessions.remove(reqId)
                    }
                }

                log.info("Attaching console to container ${container.containerName} (session=$reqId)")
                runCatching {
                    docker.attachContainerCmd(container.containerName)
                        .withStdIn(inputStream)
                        .withStdOut(true)
                        .withStdErr(true)
                        .withFollowStream(true)
                        .withLogs(false)
                        .exec(callback)
                        .awaitCompletion()
                }.onFailure { e ->
                    log.warn("Console attach failed (session=$reqId): ${e.message}")
                    out.tryConsoleOutput(reqId) { closed = true }
                }
            }
            finally {
                runCatching { inputPipe.close() }
                consoleSessions.remove(reqId)
            }
        }

        consoleSessions[reqId] = Triple(job, inputPipe, detached)
    }

    internal fun handleConsoleInput(cmd: ConsoleInput) {
        val session = consoleSessions[cmd.requestId] ?: return
        if (cmd.data.size() > 0) {
            runCatching {
                session.second.write(cmd.data.toByteArray())
                session.second.flush()
            }.onFailure { log.warn("Console input write failed (session=${cmd.requestId})", it) }
        }
    }

    internal fun handleConsoleDetach(cmd: ConsoleDetach) {
        val session = consoleSessions.remove(cmd.requestId) ?: return
        session.third.set(true)
        session.first.cancel()
        runCatching { session.second.close() }
        log.info("Console session ${cmd.requestId} detached")
    }

    // ─── File operations ──────────────────────────────────────────────────────

    internal suspend fun handleListFiles(cmd: ListFilesRequest, out: AgentOutbound) {
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val root = serverDataRoot(cmd.serverId)
                log.debug("listFiles serverId={} requestedPath={} root={} rootExists={}", cmd.serverId, cmd.path, root, Files.exists(root))
                val target = safeResolve(root, cmd.path.ifEmpty { "/" })
                log.debug("listFiles target={} exists={} isDir={}", target, Files.exists(target), Files.isDirectory(target))
                if (!Files.isDirectory(target)) error("Path not found")
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
                result.onFailure {
                    errorMessage = it.message ?: "Unknown error"
                    log.debug(errorMessage, it)
                }
            }
        }
    }

    internal suspend fun handleReadFile(cmd: ReadFileRequest, out: AgentOutbound) {
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
                result.onFailure { errorMessage = it.message ?: "Unknown error" }
            }
        }
    }

    internal suspend fun handleWriteFile(cmd: WriteFileRequest, out: AgentOutbound) {
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
                result.onFailure { errorMessage = it.message ?: "Unknown error" }
            }
        }
    }

    internal suspend fun handleDeleteFile(cmd: DeleteFileRequest, out: AgentOutbound) {
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
                result.onFailure { errorMessage = it.message ?: "Unknown error" }
            }
        }
    }

    internal suspend fun handleMakeDirectory(cmd: MakeDirectoryRequest, out: AgentOutbound) {
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
                result.onFailure { errorMessage = it.message ?: "Unknown error" }
            }
        }
    }

    internal suspend fun handleMoveFile(cmd: MoveFileRequest, out: AgentOutbound) {
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
                result.onFailure { errorMessage = it.message ?: "Unknown error" }
            }
        }
    }

    internal suspend fun handleCopyFile(cmd: CopyFileRequest, out: AgentOutbound) {
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val root = serverDataRoot(cmd.serverId)
                val src = safeResolve(root, cmd.sourcePath)
                val dst = safeResolve(root, cmd.destinationPath)
                if (!Files.exists(src)) error("Source not found")
                if (Files.exists(dst)) error("Destination already exists")
                Files.createDirectories(dst.parent)
                if (Files.isDirectory(src)) {
                    copyRecursively(src, dst)
                }
                else {
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        out.send {
            copyFileResponse = copyFileResponse {
                requestId = cmd.requestId
                success = result.isSuccess
                result.onFailure { errorMessage = it.message ?: "Unknown error" }
            }
        }
    }

    // ─── Bulk transfers ───────────────────────────────────────────────────────

    internal suspend fun handleDownloadFile(
        cmd: DownloadFileCommand,
        bulkClient: BulkDataClient,
        out: AgentOutbound,
    ) {
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
                fileResult.onFailure { errorMessage = it.message ?: "Unknown error" }
            }
        }

        if (fileResult.isSuccess) {
            runCatching { bulkClient.uploadToMaster(identity.nodeKey, cmd.transferId, fileResult.getOrThrow()) }
                .onFailure { log.error("Download transfer ${cmd.transferId}: bulk upload failed", it) }
        }
    }

    internal suspend fun handleUploadFile(
        cmd: UploadFileCommand,
        bulkClient: BulkDataClient,
        out: AgentOutbound,
    ) {
        val result = runCatching {
            val root = serverDataRoot(cmd.serverId)
            val destPath = safeResolve(root, cmd.path)
            bulkClient.receiveFromMaster(identity.nodeKey, cmd.transferId, destPath)
        }
        out.send {
            uploadFileResponse = uploadFileResponse {
                requestId = cmd.requestId
                success = result.isSuccess
                result.onFailure { errorMessage = it.message ?: "Unknown error" }
            }
        }
    }

    internal suspend fun handleDownloadBackup(
        cmd: DownloadBackupCommand,
        bulkClient: BulkDataClient,
        out: AgentOutbound,
    ) {
        val fileResult = runCatching {
            withContext(Dispatchers.IO) {
                if (!cmd.backupId.matches(Regex("^[0-9a-fA-F-]{36}$")))
                    error("Invalid backup id")
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
                fileResult.onFailure { errorMessage = it.message ?: "Unknown error" }
            }
        }
        if (fileResult.isSuccess) {
            runCatching { bulkClient.uploadToMaster(identity.nodeKey, cmd.transferId, fileResult.getOrThrow()) }
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
        if (!resolved.startsWith(root)) {
            error("Path traversal detected")
        }
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

    // ─── General helpers ──────────────────────────────────────────────────────

    private fun generateRsyncPassword(): String {
        // Alphanumeric charset only — intentional security invariant.
        // The password is echoed unquoted into the rsyncd secrets file and interpolated
        // into a sh -c script; shell metacharacters would cause injection. Do not widen.
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = java.security.SecureRandom()
        return (1..32).map { chars[random.nextInt(chars.length)] }
            .joinToString("")
    }

    private fun nowTimestamp() = timestamp {
        val now = Instant.now()
        seconds = now.epochSecond
        nanos = now.nano
    }
}
