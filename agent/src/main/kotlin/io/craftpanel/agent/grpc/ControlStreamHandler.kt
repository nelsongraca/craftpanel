package io.craftpanel.agent.grpc

import com.craftpanel.agent.v1.*
import com.google.protobuf.timestamp
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.docker.MetricsCollector
import io.grpc.ManagedChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class ControlStreamHandler(
    private val identity: NodeIdentity,
    private val containerManager: ContainerManager,
    private val metricsCollector: MetricsCollector,
) {

    private val log = LoggerFactory.getLogger(ControlStreamHandler::class.java)

    suspend fun run(channel: ManagedChannel): Unit = coroutineScope {
        val stub = ControlServiceGrpcKt.ControlServiceCoroutineStub(channel)
        val outbound = MutableSharedFlow<AgentMessage>(extraBufferCapacity = 64)

        val stream = stub.control(outbound)

        // Send NodeStateSnapshot as the first message
        val snapshot = buildStateSnapshot()
        outbound.emit(agentMessage {
            nodeId = identity.nodeId
            nodeState = snapshot
        })
        log.info("Sent NodeStateSnapshot with ${snapshot.containersCount} containers")

        // Periodic metrics loop
        launch {
            while (true) {
                delay(60.seconds)
                val metrics = metricsCollector.collect()
                outbound.emit(agentMessage {
                    nodeId = identity.nodeId
                    nodeMetrics = metrics
                })

                // Per-container metrics
                containerManager.listRunningContainerIds()
                    .forEach { (serverId, containerId) ->
                        metricsCollector.collectContainerMetrics(serverId, containerId)
                            ?.let { cm ->
                                outbound.emit(agentMessage {
                                    nodeId = identity.nodeId
                                    containerMetrics = cm
                                })
                            }
                        metricsCollector.collectPlayerCount(serverId, containerId)
                            ?.let { pu ->
                                outbound.emit(agentMessage {
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
                msg.hasStopContainer()       -> handleStop(msg.stopContainer, outbound)
                msg.hasRestartContainer()    -> handleRestart(msg.restartContainer, outbound)
                msg.hasRemoveContainer()     -> handleRemove(msg.removeContainer)
                msg.hasPullImage()           -> handlePullImage(msg.pullImage)
                msg.hasShutdown()            -> handleShutdown(msg.shutdown, outbound)
                msg.hasTriggerBackup()       -> launch { handleTriggerBackup(msg.triggerBackup, outbound) }
                msg.hasDeleteBackup()        -> launch { handleDeleteBackup(msg.deleteBackup) }
                msg.hasPrepareRsyncReceive() -> launch { handlePrepareRsyncReceive(msg.prepareRsyncReceive, outbound) }
                msg.hasStartRsync()          -> launch { handleStartRsync(msg.startRsync, outbound) }
                msg.hasSendRcon()            -> launch { handleSendRcon(msg.sendRcon) }
                else                         -> log.warn("Unhandled master message: ${msg.payloadCase}")
            }
        }
    }

    internal fun buildStateSnapshot(): NodeStateSnapshot {
        val containers = containerManager.listContainers()
        return nodeStateSnapshot {
            this.containers.addAll(containers)
            recordedAt = timestamp {
                val now = Instant.now()
                seconds = now.epochSecond
                nanos = now.nano
            }
        }
    }

    internal suspend fun handleCreate(cmd: CreateContainerCommand, outbound: MutableSharedFlow<AgentMessage>) {
        log.info("Creating container ${cmd.containerName} for server ${cmd.serverId}")
        runCatching { containerManager.createContainer(cmd) }
            .onSuccess { dockerContainerId ->
                outbound.emit(agentMessage {
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

    internal suspend fun handleStart(cmd: StartContainerCommand, outbound: MutableSharedFlow<AgentMessage>) {
        log.info("Starting container ${cmd.containerName}")
        runCatching { containerManager.startContainer(cmd.containerName) }
            .onSuccess {
                outbound.emit(agentMessage {
                    nodeId = identity.nodeId
                    serverStatus = serverStatusUpdate {
                        serverId = cmd.serverId
                        status = ServerStatusUpdate.ServerStatus.HEALTHY
                    }
                })
            }
            .onFailure {
                log.error("Failed to start container ${cmd.containerName}", it)
                outbound.emit(agentMessage {
                    nodeId = identity.nodeId
                    serverStatus = serverStatusUpdate {
                        serverId = cmd.serverId
                        status = ServerStatusUpdate.ServerStatus.UNHEALTHY
                    }
                })
            }
    }

    internal suspend fun handleStop(cmd: StopContainerCommand, outbound: MutableSharedFlow<AgentMessage>) {
        log.info("Stopping container ${cmd.containerName}")
        runCatching { containerManager.stopContainer(cmd.containerName, cmd.timeoutSeconds, cmd.stopCommand) }
            .onSuccess {
                outbound.emit(agentMessage {
                    nodeId = identity.nodeId
                    serverStatus = serverStatusUpdate {
                        serverId = cmd.serverId
                        status = ServerStatusUpdate.ServerStatus.STOPPED
                    }
                })
            }
            .onFailure {
                log.error("Failed to stop container ${cmd.containerName}", it)
                outbound.emit(agentMessage {
                    nodeId = identity.nodeId
                    serverStatus = serverStatusUpdate {
                        serverId = cmd.serverId
                        status = ServerStatusUpdate.ServerStatus.UNHEALTHY
                    }
                })
            }
    }

    internal suspend fun handleRestart(cmd: RestartContainerCommand, outbound: MutableSharedFlow<AgentMessage>) {
        log.info("Restarting container ${cmd.containerName}")
        runCatching {
            containerManager.stopContainer(cmd.containerName, cmd.timeoutSeconds, cmd.stopCommand)
            containerManager.startContainer(cmd.containerName)
        }
            .onSuccess {
                outbound.emit(agentMessage {
                    nodeId = identity.nodeId
                    serverStatus = serverStatusUpdate {
                        serverId = cmd.serverId
                        status = ServerStatusUpdate.ServerStatus.HEALTHY
                    }
                })
            }
            .onFailure {
                log.error("Failed to restart container ${cmd.containerName}", it)
                outbound.emit(agentMessage {
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

    internal suspend fun handleTriggerBackup(cmd: TriggerBackupCommand, outbound: MutableSharedFlow<AgentMessage>) {
        log.info("Backup ${cmd.backupId}: starting for server ${cmd.serverId} → ${cmd.destinationPath}")

        val dataPath = containerManager.getContainerDataPath(cmd.containerName)
        if (dataPath == null) {
            log.error("Backup ${cmd.backupId}: cannot find /data mount for container ${cmd.containerName}")
            outbound.emit(agentMessage {
                nodeId = identity.nodeId
                backupComplete = backupCompleteUpdate {
                    backupId = cmd.backupId
                    serverId = cmd.serverId
                    success = false
                    errorMessage = "Cannot find /data mount for container ${cmd.containerName}"
                    completedAt = timestamp {
                        val now = Instant.now()
                        seconds = now.epochSecond
                        nanos = now.nano
                    }
                }
            })
            return
        }

        outbound.emit(agentMessage {
            nodeId = identity.nodeId
            backupProgress = backupProgressUpdate {
                backupId = cmd.backupId
                serverId = cmd.serverId
                percentComplete = 0
            }
        })

        runCatching {
            withContext(Dispatchers.IO) {
                val destFile = File(cmd.destinationPath)
                destFile.parentFile?.mkdirs()

                val process = ProcessBuilder("tar", "-czf", cmd.destinationPath, "-C", dataPath, ".")
                    .redirectErrorStream(true)
                    .start()

                outbound.emit(agentMessage {
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

                File(cmd.destinationPath).length()
            }
        }.onSuccess { sizeBytes ->
            log.info("Backup ${cmd.backupId}: completed, size=$sizeBytes bytes")
            outbound.emit(agentMessage {
                nodeId = identity.nodeId
                backupComplete = backupCompleteUpdate {
                    backupId = cmd.backupId
                    serverId = cmd.serverId
                    success = true
                    filePath = cmd.destinationPath
                    this.sizeBytes = sizeBytes
                    completedAt = timestamp {
                        val now = Instant.now()
                        seconds = now.epochSecond
                        nanos = now.nano
                    }
                }
            })
        }
            .onFailure { ex ->
                log.error("Backup ${cmd.backupId}: failed", ex)
                outbound.emit(agentMessage {
                    nodeId = identity.nodeId
                    backupComplete = backupCompleteUpdate {
                        backupId = cmd.backupId
                        serverId = cmd.serverId
                        success = false
                        errorMessage = ex.message ?: "Unknown error"
                        completedAt = timestamp {
                            val now = Instant.now()
                            seconds = now.epochSecond
                            nanos = now.nano
                        }
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

    internal suspend fun handlePrepareRsyncReceive(
        cmd: PrepareRsyncReceiveCommand,
        outbound: MutableSharedFlow<AgentMessage>,
    ) {
        log.info("Migration ${cmd.migrationId}: preparing rsync receiver on port ${cmd.port} → ${cmd.destinationPath}")
        runCatching {
            withContext(Dispatchers.IO) {
                val password = generateRsyncPassword()
                containerManager.startRsyncdContainer(
                    migrationId = cmd.migrationId,
                    port = cmd.port,
                    destPath = cmd.destinationPath,
                    password = password,
                    rsyncImage = cmd.rsyncImage.ifEmpty { "alpine" },
                )
                password
            }
        }.onSuccess { password ->
            outbound.emit(agentMessage {
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
        outbound: MutableSharedFlow<AgentMessage>,
    ) {
        log.info("Migration ${cmd.migrationId}: starting rsync transfer (final=${cmd.isFinalPass})")
        runCatching {
            withContext(Dispatchers.IO) {
                containerManager.runRsyncTransfer(
                    migrationId = cmd.migrationId,
                    sourcePath = cmd.sourcePath,
                    destIp = cmd.destinationIp,
                    destPort = cmd.destinationPort,
                    password = cmd.rsyncPassword,
                    isFinalPass = cmd.isFinalPass,
                    rsyncImage = cmd.rsyncImage.ifEmpty { "alpine" },
                    onProgress = { bytes, total, pct, phase ->
                        outbound.tryEmit(agentMessage {
                            nodeId = identity.nodeId
                            rsyncProgress = rsyncProgressUpdate {
                                migrationId = cmd.migrationId
                                isFinalPass = cmd.isFinalPass
                                bytesTransferred = bytes
                                totalBytes = total
                                percentComplete = pct
                                this.phase = phase
                                recordedAt = timestamp {
                                    val now = Instant.now()
                                    seconds = now.epochSecond
                                    nanos = now.nano
                                }
                            }
                        })
                    },
                )
            }
        }.onSuccess { success ->
            outbound.emit(agentMessage {
                nodeId = identity.nodeId
                rsyncComplete = rsyncCompleteUpdate {
                    migrationId = cmd.migrationId
                    isFinalPass = cmd.isFinalPass
                    this.success = success
                    if (!success) errorMessage = "rsync exited with non-zero code"
                    completedAt = timestamp {
                        val now = Instant.now()
                        seconds = now.epochSecond
                        nanos = now.nano
                    }
                }
            })
            log.info("Migration ${cmd.migrationId}: rsync transfer complete (success=$success)")
        }
            .onFailure { ex ->
                log.error("Migration ${cmd.migrationId}: rsync transfer error", ex)
                outbound.emit(agentMessage {
                    nodeId = identity.nodeId
                    rsyncComplete = rsyncCompleteUpdate {
                        migrationId = cmd.migrationId
                        isFinalPass = cmd.isFinalPass
                        success = false
                        errorMessage = ex.message ?: "Unknown error"
                        completedAt = timestamp {
                            val now = Instant.now()
                            seconds = now.epochSecond
                            nanos = now.nano
                        }
                    }
                })
            }
    }

    internal fun handleSendRcon(cmd: SendRconCommand) {
        log.debug("RCON exec on server ${cmd.serverId}: ${cmd.command}")
        containerManager.execRconCommand(cmd.serverId, cmd.command)
    }

    private fun generateRsyncPassword(): String {
        // Alphanumeric charset only — intentional security invariant.
        // The password is echoed unquoted into the rsyncd secrets file and interpolated
        // into a sh -c script; shell metacharacters would cause injection. Do not widen.
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = java.security.SecureRandom()
        return (1..32).map { chars[random.nextInt(chars.length)] }
            .joinToString("")
    }

    internal suspend fun handleShutdown(cmd: ShutdownCommand, outbound: MutableSharedFlow<AgentMessage>) {
        log.info("Shutdown requested — stopping all containers gracefully")
        val (graceful, forced) = containerManager.shutdownAll(cmd.timeoutSeconds)
        outbound.emit(agentMessage {
            nodeId = identity.nodeId
            shutdownAcknowledge = shutdownAcknowledgeUpdate {
                gracefulCount = graceful
                forcedCount = forced
            }
        })
    }
}
