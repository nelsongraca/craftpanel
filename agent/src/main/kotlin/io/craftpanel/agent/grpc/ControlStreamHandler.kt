package io.craftpanel.agent.grpc

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.Frame
import com.google.protobuf.ByteString
import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.docker.MetricsCollector
import io.craftpanel.agent.grpc.handlers.*
import io.craftpanel.proto.*
import io.grpc.ManagedChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

class ControlStreamHandler(
    private val identity: NodeIdentity,
    private val config: AgentConfig,
    private val containerManager: ContainerManager,
    private val metricsCollector: MetricsCollector,
    private val docker: DockerClient,
    private val container: ContainerHandler,
    private val backup: BackupHandler = BackupHandler(config),
    private val migration: MigrationHandler = MigrationHandler(config, containerManager),
    private val file: FileHandler = FileHandler(config, identity.nodeKey),
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
                msg.hasTriggerBackup()       -> launch { dispatch { backup.handleTriggerBackup(msg.triggerBackup, out) } }
                msg.hasDeleteBackup()        -> launch { dispatch { backup.handleDeleteBackup(msg.deleteBackup) } }
                msg.hasPrepareRsyncReceive() -> launch { dispatch { migration.handlePrepareRsyncReceive(msg.prepareRsyncReceive, out) } }
                msg.hasStartRsync()          -> launch { dispatch { migration.handleStartRsync(msg.startRsync, out) } }
                msg.hasSendRcon()            -> launch { dispatch { migration.handleSendRcon(msg.sendRcon) } }
                // Console
                msg.hasConsoleAttach()       -> launch { dispatch { handleConsoleAttach(msg.consoleAttach, out) } }
                msg.hasConsoleInput()        -> dispatch { handleConsoleInput(msg.consoleInput) }
                msg.hasConsoleDetach()       -> dispatch { handleConsoleDetach(msg.consoleDetach) }
                // File ops (unary) — each in its own coroutine so it does not block the stream
                msg.hasListFiles()           -> launch { dispatch { file.handleListFiles(msg.listFiles, out) } }
                msg.hasReadFile()            -> launch { dispatch { file.handleReadFile(msg.readFile, out) } }
                msg.hasWriteFile()           -> launch { dispatch { file.handleWriteFile(msg.writeFile, out) } }
                msg.hasDeleteFile()          -> launch { dispatch { file.handleDeleteFile(msg.deleteFile, out) } }
                msg.hasMakeDirectory()       -> launch { dispatch { file.handleMakeDirectory(msg.makeDirectory, out) } }
                msg.hasMoveFile()            -> launch { dispatch { file.handleMoveFile(msg.moveFile, out) } }
                msg.hasCopyFile()            -> launch { dispatch { file.handleCopyFile(msg.copyFile, out) } }
                // Bulk transfers — agent dials master's BulkDataService
                msg.hasDownloadFile()        -> launch { dispatch { file.handleDownloadFile(msg.downloadFile, bulkClient, out) } }
                msg.hasUploadFile()          -> launch { dispatch { file.handleUploadFile(msg.uploadFile, bulkClient, out) } }
                msg.hasDownloadBackup()      -> launch { dispatch { file.handleDownloadBackup(msg.downloadBackup, bulkClient, out) } }
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
            } finally {
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
}
