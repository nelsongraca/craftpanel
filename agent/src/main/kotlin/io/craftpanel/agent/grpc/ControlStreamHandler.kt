package io.craftpanel.agent.grpc

import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.*
import io.craftpanel.agent.grpc.handlers.*
import io.craftpanel.proto.*
import io.grpc.ManagedChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

class ControlStreamHandler(
    private val identity: NodeIdentity,
    private val config: AgentConfig,
    private val containerManager: ContainerManager,
    private val metricsCollector: MetricsCollector,
    private val routerSupervisor: RouterSupervisor,
    private val container: ContainerHandler,
    private val eventWatcher: ContainerEventWatcher,
    private val backup: BackupHandler = BackupHandler(config),
    private val rsyncMigrator: RsyncMigrator,
    private val migration: MigrationHandler = MigrationHandler(config, containerManager, rsyncMigrator),
    private val file: FileHandler = FileHandler(config, identity.nodeKey),
    private val console: ConsoleHandler
) {

    private val log = LoggerFactory.getLogger(ControlStreamHandler::class.java)

    suspend fun run(channel: ManagedChannel): Unit = coroutineScope {
        val stub = ControlServiceGrpcKt.ControlServiceCoroutineStub(channel)
        val bulkClient = BulkDataClient(channel)
        val outboundChannel = Channel<AgentMessage>(capacity = 64)
        val out = AgentOutbound(outboundChannel, identity.nodeId)

        val stream = stub.control(outboundChannel.receiveAsFlow())

        // Send NodeStateSnapshot as the first message
        val snapshot = buildStateSnapshot()
        outboundChannel.send(
            agentMessage {
                nodeId = identity.nodeId
                nodeState = snapshot
            }
        )
        log.info("Sent NodeStateSnapshot with ${snapshot.containersCount} containers")

        // Periodic metrics loop
        val metricsInterval = config.metricsPollIntervalSeconds.toLong().seconds
        launch {
            while (true) {
                delay(metricsInterval)
                Heartbeat.beat()
                val routerRunning = routerSupervisor.isRunning
                val metrics = metricsCollector.collect()
                out.send {
                    nodeMetrics = metrics.toBuilder()
                        .setRouterRunning(routerRunning)
                        .build()
                }

                containerManager.listRunningContainerIds()
                    .forEach { (serverId, containerId) ->
                        metricsCollector.collectContainerMetrics(serverId, containerId)
                            ?.let { cm -> out.send { containerMetrics = cm } }
                        metricsCollector.collectPlayerCount(serverId, containerId)
                            ?.let { pu -> out.send { playerUpdate = pu } }
                    }
            }
        }

        // Near-instant crash signal: report managed-container deaths to master immediately.
        // Master decides whether to restart (bounded). Closed when this stream scope ends;
        // the periodic snapshot reconcile is the backstop if an event is missed.
        val eventStream = eventWatcher.watch(
            shouldReport = { serverId -> containerManager.shouldReportDie(serverId) },
            onContainerCrash = { serverId -> out.tryServerStatus(serverId, ServerStatusUpdate.ServerStatus.UNHEALTHY) },
            onContainerStopped = { serverId -> out.tryServerStatus(serverId, ServerStatusUpdate.ServerStatus.STOPPED) }
        )
        coroutineContext.job.invokeOnCompletion { runCatching { eventStream.close() } }

        // Process inbound commands from master
        stream.collect { msg ->
            log.debug("Received master command: {}", msg.payloadCase)
            when {
                msg.hasStartContainer() -> launch { dispatch { container.handleStart(msg.startContainer, out) } }

                msg.hasStopContainer() -> launch { dispatch { container.handleStop(msg.stopContainer, out) } }

                msg.hasRestartContainer() -> launch { dispatch { container.handleRestart(msg.restartContainer, out) } }

                msg.hasRemoveContainer() -> dispatch { container.handleRemove(msg.removeContainer, out) }

                msg.hasShutdown() -> dispatch { container.handleShutdown(msg.shutdown, out) }

                msg.hasTriggerBackup() -> launch { dispatch { backup.handleTriggerBackup(msg.triggerBackup, out) } }

                msg.hasDeleteBackup() -> launch { dispatch { backup.handleDeleteBackup(msg.deleteBackup) } }

                msg.hasPrepareRsyncReceive() -> launch { dispatch { migration.handlePrepareRsyncReceive(msg.prepareRsyncReceive, out) } }

                msg.hasStartRsync() -> launch { dispatch { migration.handleStartRsync(msg.startRsync, out) } }

                msg.hasSendRcon() -> launch { dispatch { migration.handleSendRcon(msg.sendRcon) } }

                // Console
                msg.hasConsoleAttach() -> launch { dispatch { console.handleConsoleAttach(msg.consoleAttach, out) } }

                msg.hasConsoleInput() -> dispatch { console.handleConsoleInput(msg.consoleInput) }

                msg.hasConsoleDetach() -> dispatch { console.handleConsoleDetach(msg.consoleDetach) }

                msg.hasFetchContainerLogs() -> launch { dispatch { console.handleFetchContainerLogs(msg.fetchContainerLogs, out) } }

                // File ops (unary) — each in its own coroutine so it does not block the stream
                msg.hasListFiles() -> launch { dispatch { file.handleListFiles(msg.listFiles, out) } }

                msg.hasReadFile() -> launch { dispatch { file.handleReadFile(msg.readFile, out) } }

                msg.hasWriteFile() -> launch { dispatch { file.handleWriteFile(msg.writeFile, out) } }

                msg.hasDeleteFile() -> launch { dispatch { file.handleDeleteFile(msg.deleteFile, out) } }

                msg.hasMakeDirectory() -> launch { dispatch { file.handleMakeDirectory(msg.makeDirectory, out) } }

                msg.hasMoveFile() -> launch { dispatch { file.handleMoveFile(msg.moveFile, out) } }

                msg.hasCopyFile() -> launch { dispatch { file.handleCopyFile(msg.copyFile, out) } }

                // Bulk transfers — agent dials master's BulkDataService
                msg.hasDownloadFile() -> launch { dispatch { file.handleDownloadFile(msg.downloadFile, bulkClient, out) } }

                msg.hasUploadFile() -> launch { dispatch { file.handleUploadFile(msg.uploadFile, bulkClient, out) } }

                msg.hasDownloadBackup() -> launch { dispatch { file.handleDownloadBackup(msg.downloadBackup, bulkClient, out) } }

                msg.hasRebuildSymlinks() -> launch { dispatch { rebuildSymlinksFromSnapshot(msg.rebuildSymlinks) } }

                else -> log.warn("Unhandled master message: ${msg.payloadCase}")
            }
        }
    }

    private suspend fun dispatch(block: suspend () -> Unit) {
        runCatching { block() }
            .onFailure { if (it is CancellationException) throw it else log.error("Unexpected handler failure", it) }
    }

    private fun isSwarmActive(): Boolean = containerManager.isSwarmActive()

    internal suspend fun rebuildSymlinksFromSnapshot(cmd: RebuildSymlinksCommand) = withContext(Dispatchers.IO) {
        cmd.serversList.forEach { entry ->
            runCatching {
                val canonicalPath = serverDataRoot(config.dataBasePath, entry.serverId)
                if (java.nio.file.Files.exists(canonicalPath)) {
                    SymlinkMaintainer.createServerNameSymlink(config.serversByNameRoot, entry.serverName, canonicalPath)
                }
            }.onFailure { log.warn("Rebuild: failed servers-by-name symlink for ${entry.serverId}", it) }
        }
        cmd.backupsList.forEach { entry ->
            runCatching {
                val canonicalFile = java.nio.file.Paths.get(entry.filePath)
                if (java.nio.file.Files.exists(canonicalFile)) {
                    SymlinkMaintainer.createBackupSymlink(
                        config.backupsByServerRoot,
                        entry.serverName,
                        entry.createdAtFormatted,
                        canonicalFile
                    )
                }
            }.onFailure { log.warn("Rebuild: failed backups-by-server symlink for ${entry.backupId}", it) }
        }
    }

    internal fun buildStateSnapshot(): NodeStateSnapshot {
        val containers = containerManager.listContainers()
        return nodeStateSnapshot {
            this.containers.addAll(containers)
            recordedAt = nowTimestamp()
            routerRunning = routerSupervisor.isRunning
            swarmActive = isSwarmActive()
        }
    }
}
