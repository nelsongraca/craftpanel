package io.craftpanel.agent.grpc.handlers

import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.grpc.AgentOutbound
import io.craftpanel.proto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class MigrationHandler(
    private val config: AgentConfig,
    private val containerManager: ContainerManager,
) {

    private val log = LoggerFactory.getLogger(MigrationHandler::class.java)

    suspend fun handlePrepareRsyncReceive(cmd: PrepareRsyncReceiveCommand, out: AgentOutbound) {
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

    suspend fun handleStartRsync(cmd: StartRsyncCommand, out: AgentOutbound) {
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
                    if (!success) {
                        errorMessage = "rsync exited with non-zero code"
                        errorCode = ErrorCode.INTERNAL
                    }
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
                        errorCode = ErrorCode.INTERNAL
                        completedAt = nowTimestamp()
                    }
                }
            }
    }

    fun handleSendRcon(cmd: SendRconCommand) {
        log.debug("RCON exec on server ${cmd.serverId}: ${cmd.command}")
        containerManager.execRconCommand(cmd.serverId, cmd.command)
    }
}
