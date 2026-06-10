package io.craftpanel.agent.grpc

import com.craftpanel.agent.v1.*
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.Frame
import com.google.protobuf.ByteString
import com.google.protobuf.timestamp
import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.docker.MetricsCollector
import io.grpc.ManagedChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.receiveAsFlow
import org.slf4j.LoggerFactory
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

class ControlStreamHandler(
    private val identity: NodeIdentity,
    private val config: AgentConfig,
    private val containerManager: ContainerManager,
    private val metricsCollector: MetricsCollector,
    private val docker: DockerClient,
) {

    private val log = LoggerFactory.getLogger(ControlStreamHandler::class.java)

    // Active console sessions: request_id → (job, inputPipe)
    private val consoleSessions = ConcurrentHashMap<String, Pair<Job, PipedOutputStream>>()

    suspend fun run(channel: ManagedChannel): Unit = coroutineScope {
        val stub = ControlServiceGrpcKt.ControlServiceCoroutineStub(channel)
        val bulkClient = BulkDataClient(channel)
        val outbound = Channel<AgentMessage>(capacity = 64)

        val stream = stub.control(outbound.receiveAsFlow())

        // Send NodeStateSnapshot as the first message
        val snapshot = buildStateSnapshot()
        outbound.send(agentMessage {
            nodeId = identity.nodeId
            nodeState = snapshot
        })
        log.info("Sent NodeStateSnapshot with ${snapshot.containersCount} containers")

        // Periodic metrics loop
        launch {
            while (true) {
                delay(60.seconds)
                val metrics = metricsCollector.collect()
                outbound.send(agentMessage {
                    nodeId = identity.nodeId
                    nodeMetrics = metrics
                })

                containerManager.listRunningContainerIds()
                    .forEach { (serverId, containerId) ->
                        metricsCollector.collectContainerMetrics(serverId, containerId)
                            ?.let { cm ->
                                outbound.send(agentMessage {
                                    nodeId = identity.nodeId
                                    containerMetrics = cm
                                })
                            }
                        metricsCollector.collectPlayerCount(serverId, containerId)
                            ?.let { pu ->
                                outbound.send(agentMessage {
                                    nodeId = identity.nodeId
                                    playerUpdate = pu
                                })
                            }
                    }
            }
        }

        // Process inbound commands from master
        stream.collect { msg ->
            log.debug("Received master command: {}", msg.payloadCase)
            when {
                msg.hasCreateContainer()     -> handleCreate(msg.createContainer, outbound)
                msg.hasStartContainer()      -> handleStart(msg.startContainer, outbound)
                msg.hasStopContainer()       -> launch { handleStop(msg.stopContainer, outbound) }
                msg.hasRestartContainer()    -> launch { handleRestart(msg.restartContainer, outbound) }
                msg.hasRemoveContainer()     -> handleRemove(msg.removeContainer)
                msg.hasPullImage()           -> handlePullImage(msg.pullImage)
                msg.hasShutdown()            -> handleShutdown(msg.shutdown, outbound)
                msg.hasTriggerBackup()       -> launch { handleTriggerBackup(msg.triggerBackup, outbound) }
                msg.hasDeleteBackup()        -> launch { handleDeleteBackup(msg.deleteBackup) }
                msg.hasPrepareRsyncReceive() -> launch { handlePrepareRsyncReceive(msg.prepareRsyncReceive, outbound) }
                msg.hasStartRsync()          -> launch { handleStartRsync(msg.startRsync, outbound) }
                msg.hasSendRcon()            -> launch { handleSendRcon(msg.sendRcon) }
                // Console
                msg.hasConsoleAttach()       -> launch { handleConsoleAttach(msg.consoleAttach, outbound) }
                msg.hasConsoleInput()        -> handleConsoleInput(msg.consoleInput)
                msg.hasConsoleDetach()       -> handleConsoleDetach(msg.consoleDetach)
                // File ops (unary) — each in its own coroutine so it does not block the stream
                msg.hasListFiles()           -> launch { handleListFiles(msg.listFiles, outbound) }
                msg.hasReadFile()            -> launch { handleReadFile(msg.readFile, outbound) }
                msg.hasWriteFile()           -> launch { handleWriteFile(msg.writeFile, outbound) }
                msg.hasDeleteFile()          -> launch { handleDeleteFile(msg.deleteFile, outbound) }
                msg.hasMakeDirectory()       -> launch { handleMakeDirectory(msg.makeDirectory, outbound) }
                msg.hasMoveFile()            -> launch { handleMoveFile(msg.moveFile, outbound) }
                msg.hasCopyFile()            -> launch { handleCopyFile(msg.copyFile, outbound) }
                // Bulk transfers — agent dials master's BulkDataService
                msg.hasDownloadFile()        -> launch { handleDownloadFile(msg.downloadFile, bulkClient, outbound) }
                msg.hasUploadFile()          -> launch { handleUploadFile(msg.uploadFile, bulkClient, outbound) }
                else                         -> log.warn("Unhandled master message: ${msg.payloadCase}")
            }
        }
    }

    internal fun buildStateSnapshot(): NodeStateSnapshot {
        val containers = containerManager.listContainers()
        return nodeStateSnapshot {
            this.containers.addAll(containers)
            recordedAt = nowTimestamp()
        }
    }

    // ─── Container lifecycle ──────────────────────────────────────────────────

    internal suspend fun handleCreate(cmd: CreateContainerCommand, outbound: SendChannel<AgentMessage>) {
        log.info("Creating container ${cmd.containerName} for server ${cmd.serverId}")
        runCatching {
            withContext(Dispatchers.IO) { containerManager.pullImage(cmd.image) }
            val cmdWithMount = cmd.toBuilder()
                .addMounts(volumeMount {
                    hostPath = "${config.hostDataBasePath}/servers/${cmd.serverId}"
                    containerPath = "/data"
                    readOnly = false
                })
                .build()
            containerManager.createContainer(cmdWithMount)
        }
            .onSuccess { dockerContainerId ->
                outbound.send(agentMessage {
                    nodeId = identity.nodeId
                    serverStatus = serverStatusUpdate {
                        serverId = cmd.serverId
                        status = ServerStatusUpdate.ServerStatus.STOPPED
                        containerId = dockerContainerId
                    }
                })
            }
            .onFailure { log.error("Failed to create container ${cmd.containerName}", it) }
    }

    internal suspend fun handleStart(cmd: StartContainerCommand, outbound: SendChannel<AgentMessage>) {
        log.info("Starting container ${cmd.containerName}")
        val expectedDataPath = "${config.hostDataBasePath}/servers/${cmd.serverId}"
        val currentDataPath = containerManager.getContainerDataPath(cmd.containerName)
        if (currentDataPath != null && currentDataPath != expectedDataPath) {
            log.warn("Container ${cmd.containerName} has stale data mount '$currentDataPath' (expected '$expectedDataPath') — removing for recreate")
            runCatching { withContext(Dispatchers.IO) { containerManager.removeContainer(cmd.containerName, force = true) } }
                .onFailure { log.error("Failed to remove stale container ${cmd.containerName}", it) }
            outbound.send(agentMessage {
                nodeId = identity.nodeId
                serverStatus = serverStatusUpdate {
                    serverId = cmd.serverId
                    status = ServerStatusUpdate.ServerStatus.UNHEALTHY
                }
            })
            return
        }
        runCatching { containerManager.startContainer(cmd.containerName) }
            .onSuccess {
                outbound.send(agentMessage {
                    nodeId = identity.nodeId
                    serverStatus = serverStatusUpdate {
                        serverId = cmd.serverId
                        status = ServerStatusUpdate.ServerStatus.HEALTHY
                    }
                })
            }
            .onFailure {
                log.error("Failed to start container ${cmd.containerName}", it)
                outbound.send(agentMessage {
                    nodeId = identity.nodeId
                    serverStatus = serverStatusUpdate {
                        serverId = cmd.serverId
                        status = ServerStatusUpdate.ServerStatus.UNHEALTHY
                    }
                })
            }
    }

    internal suspend fun handleStop(cmd: StopContainerCommand, outbound: SendChannel<AgentMessage>) {
        log.info("Stopping container ${cmd.containerName}")
        runCatching { containerManager.stopContainer(cmd.containerName, cmd.timeoutSeconds, cmd.stopCommand) }
            .onSuccess {
                outbound.send(agentMessage {
                    nodeId = identity.nodeId
                    serverStatus = serverStatusUpdate {
                        serverId = cmd.serverId
                        status = ServerStatusUpdate.ServerStatus.STOPPED
                    }
                })
            }
            .onFailure {
                log.error("Failed to stop container ${cmd.containerName}", it)
                outbound.send(agentMessage {
                    nodeId = identity.nodeId
                    serverStatus = serverStatusUpdate {
                        serverId = cmd.serverId
                        status = ServerStatusUpdate.ServerStatus.UNHEALTHY
                    }
                })
            }
    }

    internal suspend fun handleRestart(cmd: RestartContainerCommand, outbound: SendChannel<AgentMessage>) {
        log.info("Restarting container ${cmd.containerName}")
        runCatching {
            containerManager.stopContainer(cmd.containerName, cmd.timeoutSeconds, cmd.stopCommand)
            containerManager.startContainer(cmd.containerName)
        }
            .onSuccess {
                outbound.send(agentMessage {
                    nodeId = identity.nodeId
                    serverStatus = serverStatusUpdate {
                        serverId = cmd.serverId
                        status = ServerStatusUpdate.ServerStatus.HEALTHY
                    }
                })
            }
            .onFailure {
                log.error("Failed to restart container ${cmd.containerName}", it)
                outbound.send(agentMessage {
                    nodeId = identity.nodeId
                    serverStatus = serverStatusUpdate {
                        serverId = cmd.serverId
                        status = ServerStatusUpdate.ServerStatus.UNHEALTHY
                    }
                })
            }
    }

    internal fun handleRemove(cmd: RemoveContainerCommand) {
        log.info("Removing container ${cmd.containerName} (force=${cmd.force})")
        runCatching { containerManager.removeContainer(cmd.containerName, cmd.force) }
            .onFailure { log.error("Failed to remove container ${cmd.containerName}", it) }
    }

    internal suspend fun handlePullImage(cmd: PullImageCommand) {
        log.info("Pulling image ${cmd.image} for server ${cmd.serverId}")
        runCatching { withContext(Dispatchers.IO) { containerManager.pullImage(cmd.image) } }
            .onFailure { log.error("Failed to pull image ${cmd.image}", it) }
    }

    internal suspend fun handleShutdown(cmd: ShutdownCommand, outbound: SendChannel<AgentMessage>) {
        log.info("Shutdown requested — stopping all containers gracefully")
        val (graceful, forced) = containerManager.shutdownAll(cmd.timeoutSeconds)
        outbound.send(agentMessage {
            nodeId = identity.nodeId
            shutdownAcknowledge = shutdownAcknowledgeUpdate {
                gracefulCount = graceful
                forcedCount = forced
            }
        })
    }

    // ─── Backup ───────────────────────────────────────────────────────────────

    internal suspend fun handleTriggerBackup(cmd: TriggerBackupCommand, outbound: SendChannel<AgentMessage>) {
        val sourceDir = "${config.dataBasePath}/servers/${cmd.serverId}"
        val destPath = "${config.dataBasePath}/backups/${cmd.backupId}.tar.gz"
        log.info("Backup ${cmd.backupId}: starting for server ${cmd.serverId} → $destPath")

        outbound.send(agentMessage {
            nodeId = identity.nodeId
            backupProgress = backupProgressUpdate {
                backupId = cmd.backupId
                serverId = cmd.serverId
                percentComplete = 0
            }
        })

        runCatching {
            withContext(Dispatchers.IO) {
                val destFile = File(destPath)
                destFile.parentFile?.mkdirs()

                val process = ProcessBuilder("tar", "-czf", destPath, "-C", sourceDir, ".")
                    .redirectErrorStream(true)
                    .start()

                outbound.send(agentMessage {
                    nodeId = identity.nodeId
                    backupProgress = backupProgressUpdate {
                        backupId = cmd.backupId
                        serverId = cmd.serverId
                        percentComplete = 50
                    }
                })

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
            outbound.send(agentMessage {
                nodeId = identity.nodeId
                backupComplete = backupCompleteUpdate {
                    backupId = cmd.backupId
                    serverId = cmd.serverId
                    success = true
                    filePath = destPath
                    this.sizeBytes = sizeBytes
                    completedAt = nowTimestamp()
                }
            })
        }
            .onFailure { ex ->
                log.error("Backup ${cmd.backupId}: failed", ex)
                outbound.send(agentMessage {
                    nodeId = identity.nodeId
                    backupComplete = backupCompleteUpdate {
                        backupId = cmd.backupId
                        serverId = cmd.serverId
                        success = false
                        errorMessage = ex.message ?: "Unknown error"
                        completedAt = nowTimestamp()
                    }
                })
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
        outbound: SendChannel<AgentMessage>,
    ) {
        val destPath = "${config.dataBasePath}/servers/${cmd.serverId}"
        log.info("Migration ${cmd.migrationId}: preparing rsync receiver on port ${cmd.port} → $destPath")
        runCatching {
            withContext(Dispatchers.IO) {
                val password = generateRsyncPassword()
                containerManager.startRsyncdContainer(
                    migrationId = cmd.migrationId,
                    port = cmd.port,
                    destPath = destPath,
                    password = password,
                    rsyncImage = cmd.rsyncImage.ifEmpty { "alpine" },
                )
                password
            }
        }.onSuccess { password ->
            outbound.send(agentMessage {
                nodeId = identity.nodeId
                rsyncReady = rsyncReadyUpdate {
                    migrationId = cmd.migrationId
                    rsyncPassword = password
                }
            })
            log.info("Migration ${cmd.migrationId}: rsyncd ready")
        }
            .onFailure { ex ->
                log.error("Migration ${cmd.migrationId}: failed to start rsyncd", ex)
            }
    }

    internal suspend fun handleStartRsync(
        cmd: StartRsyncCommand,
        outbound: SendChannel<AgentMessage>,
    ) {
        val sourcePath = "${config.dataBasePath}/servers/${cmd.serverId}"
        log.info("Migration ${cmd.migrationId}: starting rsync transfer (final=${cmd.isFinalPass})")
        runCatching {
            withContext(Dispatchers.IO) {
                containerManager.runRsyncTransfer(
                    migrationId = cmd.migrationId,
                    sourcePath = sourcePath,
                    destIp = cmd.destinationIp,
                    destPort = cmd.destinationPort,
                    password = cmd.rsyncPassword,
                    isFinalPass = cmd.isFinalPass,
                    rsyncImage = cmd.rsyncImage.ifEmpty { "alpine" },
                    onProgress = { bytes, total, pct, phase ->
                        outbound.trySend(agentMessage {
                            nodeId = identity.nodeId
                            rsyncProgress = rsyncProgressUpdate {
                                migrationId = cmd.migrationId
                                isFinalPass = cmd.isFinalPass
                                bytesTransferred = bytes
                                totalBytes = total
                                percentComplete = pct
                                this.phase = phase
                                recordedAt = nowTimestamp()
                            }
                        })
                    },
                )
            }
        }.onSuccess { success ->
            outbound.send(agentMessage {
                nodeId = identity.nodeId
                rsyncComplete = rsyncCompleteUpdate {
                    migrationId = cmd.migrationId
                    isFinalPass = cmd.isFinalPass
                    this.success = success
                    if (!success) errorMessage = "rsync exited with non-zero code"
                    completedAt = nowTimestamp()
                }
            })
            log.info("Migration ${cmd.migrationId}: rsync transfer complete (success=$success)")
        }
            .onFailure { ex ->
                log.error("Migration ${cmd.migrationId}: rsync transfer error", ex)
                outbound.send(agentMessage {
                    nodeId = identity.nodeId
                    rsyncComplete = rsyncCompleteUpdate {
                        migrationId = cmd.migrationId
                        isFinalPass = cmd.isFinalPass
                        success = false
                        errorMessage = ex.message ?: "Unknown error"
                        completedAt = nowTimestamp()
                    }
                })
            }
    }

    internal fun handleSendRcon(cmd: SendRconCommand) {
        log.debug("RCON exec on server ${cmd.serverId}: ${cmd.command}")
        containerManager.execRconCommand(cmd.serverId, cmd.command)
    }

    // ─── Console ──────────────────────────────────────────────────────────────

    internal suspend fun handleConsoleAttach(cmd: ConsoleAttach, outbound: SendChannel<AgentMessage>) {
        val reqId = cmd.requestId
        if (consoleSessions.containsKey(reqId)) {
            log.warn("Console attach for already-active session $reqId — ignoring")
            return
        }

        val containers = containerManager.listContainers()
        val container = containers.find { it.serverId == cmd.serverId }
        if (container == null) {
            outbound.send(agentMessage {
                nodeId = identity.nodeId
                consoleOutput = consoleOutput {
                    requestId = reqId
                    closed = true
                }
            })
            log.warn("Console attach: server ${cmd.serverId} not found")
            return
        }

        val inputPipe = PipedOutputStream()

        @Suppress("BlockingMethodInNonBlockingContext")
        val inputStream = PipedInputStream(inputPipe)

        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                val callback = object : ResultCallback.Adapter<Frame>() {
                    override fun onNext(frame: Frame) {
                        frame.payload?.takeIf { it.isNotEmpty() }
                            ?.let { payload ->
                                outbound.trySend(agentMessage {
                                    nodeId = identity.nodeId
                                    consoleOutput = consoleOutput {
                                        requestId = reqId
                                        data = ByteString.copyFrom(payload)
                                    }
                                })
                            }
                    }

                    override fun onComplete() {
                        outbound.trySend(agentMessage {
                            nodeId = identity.nodeId
                            consoleOutput = consoleOutput {
                                requestId = reqId
                                closed = true
                            }
                        })
                        consoleSessions.remove(reqId)
                    }

                    override fun onError(t: Throwable) {
                        outbound.trySend(agentMessage {
                            nodeId = identity.nodeId
                            consoleOutput = consoleOutput {
                                requestId = reqId
                                closed = true
                            }
                        })
                        consoleSessions.remove(reqId)
                    }
                }

                log.info("Attaching console to container ${container.containerName} (session=$reqId)")
                docker.attachContainerCmd(container.containerName)
                    .withStdIn(inputStream)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(true)
                    .withLogs(true)
                    .exec(callback)
                    .awaitCompletion()
            }
            finally {
                runCatching { inputPipe.close() }
                consoleSessions.remove(reqId)
            }
        }

        consoleSessions[reqId] = Pair(job, inputPipe)
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
        session.first.cancel()
        runCatching { session.second.close() }
        log.info("Console session ${cmd.requestId} detached")
    }

    // ─── File operations ──────────────────────────────────────────────────────

    internal suspend fun handleListFiles(cmd: ListFilesRequest, outbound: SendChannel<AgentMessage>) {
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
        outbound.send(agentMessage {
            nodeId = identity.nodeId
            listFilesResponse = listFilesResponse {
                requestId = cmd.requestId
                result.onSuccess { entries.addAll(it) }
                result.onFailure {
                    errorMessage = it.message ?: "Unknown error"
                    log.debug(errorMessage, it)
                }
            }
        })
    }

    internal suspend fun handleReadFile(cmd: ReadFileRequest, outbound: SendChannel<AgentMessage>) {
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
        outbound.send(agentMessage {
            nodeId = identity.nodeId
            readFileResponse = readFileResponse {
                requestId = cmd.requestId
                result.onSuccess { (bytes, enc) ->
                    content = ByteString.copyFrom(bytes)
                    encoding = enc
                }
                result.onFailure { errorMessage = it.message ?: "Unknown error" }
            }
        })
    }

    internal suspend fun handleWriteFile(cmd: WriteFileRequest, outbound: SendChannel<AgentMessage>) {
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val root = serverDataRoot(cmd.serverId)
                val target = safeResolve(root, cmd.path)
                Files.createDirectories(target.parent)
                Files.write(target, cmd.content.toByteArray())
            }
        }
        outbound.send(agentMessage {
            nodeId = identity.nodeId
            writeFileResponse = writeFileResponse {
                requestId = cmd.requestId
                success = result.isSuccess
                result.onFailure { errorMessage = it.message ?: "Unknown error" }
            }
        })
    }

    internal suspend fun handleDeleteFile(cmd: DeleteFileRequest, outbound: SendChannel<AgentMessage>) {
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
        outbound.send(agentMessage {
            nodeId = identity.nodeId
            deleteFileResponse = deleteFileResponse {
                requestId = cmd.requestId
                success = result.isSuccess
                result.onFailure { errorMessage = it.message ?: "Unknown error" }
            }
        })
    }

    internal suspend fun handleMakeDirectory(cmd: MakeDirectoryRequest, outbound: SendChannel<AgentMessage>) {
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val root = serverDataRoot(cmd.serverId)
                val target = safeResolve(root, cmd.path)
                Files.createDirectories(target)
            }
        }
        outbound.send(agentMessage {
            nodeId = identity.nodeId
            makeDirectoryResponse = makeDirectoryResponse {
                requestId = cmd.requestId
                success = result.isSuccess
                result.onFailure { errorMessage = it.message ?: "Unknown error" }
            }
        })
    }

    internal suspend fun handleMoveFile(cmd: MoveFileRequest, outbound: SendChannel<AgentMessage>) {
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
        outbound.send(agentMessage {
            nodeId = identity.nodeId
            moveFileResponse = moveFileResponse {
                requestId = cmd.requestId
                success = result.isSuccess
                result.onFailure { errorMessage = it.message ?: "Unknown error" }
            }
        })
    }

    internal suspend fun handleCopyFile(cmd: CopyFileRequest, outbound: SendChannel<AgentMessage>) {
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val root = serverDataRoot(cmd.serverId)
                val src = safeResolve(root, cmd.sourcePath)
                val dst = safeResolve(root, cmd.destinationPath)
                if (!Files.exists(src)) error("Source not found")
                if (Files.exists(dst)) error("Destination already exists")
                Files.createDirectories(dst.parent)
                if (cmd.recursive && Files.isDirectory(src)) {
                    copyRecursively(src, dst)
                }
                else {
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        outbound.send(agentMessage {
            nodeId = identity.nodeId
            copyFileResponse = copyFileResponse {
                requestId = cmd.requestId
                success = result.isSuccess
                result.onFailure { errorMessage = it.message ?: "Unknown error" }
            }
        })
    }

    // ─── Bulk transfers ───────────────────────────────────────────────────────

    internal suspend fun handleDownloadFile(
        cmd: DownloadFileCommand,
        bulkClient: BulkDataClient,
        outbound: SendChannel<AgentMessage>,
    ) {
        val result = runCatching {
            val root = serverDataRoot(cmd.serverId)
            val filePath = safeResolve(root, cmd.path)
            if (!Files.exists(filePath)) error("File not found: ${cmd.path}")
            bulkClient.uploadToMaster(identity.nodeKey, cmd.transferId, filePath)
        }
        outbound.send(agentMessage {
            nodeId = identity.nodeId
            downloadFileResponse = downloadFileResponse {
                requestId = cmd.requestId
                success = result.isSuccess
                result.onFailure { errorMessage = it.message ?: "Unknown error" }
            }
        })
    }

    internal suspend fun handleUploadFile(
        cmd: UploadFileCommand,
        bulkClient: BulkDataClient,
        outbound: SendChannel<AgentMessage>,
    ) {
        val result = runCatching {
            val root = serverDataRoot(cmd.serverId)
            val destPath = safeResolve(root, cmd.path)
            bulkClient.receiveFromMaster(identity.nodeKey, cmd.transferId, destPath)
        }
        outbound.send(agentMessage {
            nodeId = identity.nodeId
            uploadFileResponse = uploadFileResponse {
                requestId = cmd.requestId
                success = result.isSuccess
                result.onFailure { errorMessage = it.message ?: "Unknown error" }
            }
        })
    }

    // ─── Path helpers ─────────────────────────────────────────────────────────

    private fun serverDataRoot(serverId: String): Path =
        Paths.get(config.dataBasePath, "servers", serverId)
            .normalize()

    private fun safeResolve(root: Path, relativePath: String): Path {
        val canonicalRoot = runCatching { root.toRealPath() }.getOrNull()
        log.debug("safeResolve root={} canonicalRoot={} relativePath={}", root, canonicalRoot, relativePath)
        val clean = relativePath.trimStart('/')
        val resolved = root.resolve(clean)
            .normalize()
        log.debug("safeResolve resolved={} startsWithRoot={}", resolved, resolved.startsWith(root))
        if (!resolved.startsWith(root)) {
            error("Path traversal detected")
        }
        if (canonicalRoot != null) {
            if (Files.exists(resolved)) {
                val real = runCatching { resolved.toRealPath() }.getOrElse { resolved }
                log.debug("safeResolve real={} startsWithCanonical={}", real, real.startsWith(canonicalRoot))
                if (!real.startsWith(canonicalRoot)) {
                    error("Path traversal via symlink detected")
                }
            }
            else {
                var ancestor = resolved.parent
                while (ancestor != null && !Files.exists(ancestor)) {
                    ancestor = ancestor.parent
                }
                if (ancestor != null) {
                    val realAncestor = runCatching { ancestor.toRealPath() }.getOrElse { ancestor }
                    if (!realAncestor.startsWith(canonicalRoot)) {
                        error("Path traversal via symlink detected")
                    }
                }
            }
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
