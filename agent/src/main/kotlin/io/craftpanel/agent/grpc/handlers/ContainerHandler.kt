package io.craftpanel.agent.grpc.handlers

import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.grpc.AgentOutbound
import io.craftpanel.proto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class ContainerHandler(
    private val containerManager: ContainerManager,
    private val config: AgentConfig,
) {

    private val log = LoggerFactory.getLogger(ContainerHandler::class.java)

    suspend fun handleCreate(cmd: CreateContainerCommand, out: AgentOutbound) {
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
                out.serverStatus(cmd.serverId, ServerStatusUpdate.ServerStatus.STOPPED, dockerContainerId)
            }
            .onFailure { e ->
                log.error("Failed to create container ${cmd.containerName}", e)
                out.serverStatus(cmd.serverId, ServerStatusUpdate.ServerStatus.UNHEALTHY)
            }
    }

    suspend fun handleStart(cmd: StartContainerCommand, out: AgentOutbound) {
        log.info("Starting container ${cmd.containerName}")
        val expectedDataPath = "${config.hostDataBasePath}/servers/${cmd.serverId}"
        val currentDataPath = containerManager.getContainerDataPath(cmd.containerName)
        if (currentDataPath != null && currentDataPath != expectedDataPath) {
            log.warn("Container ${cmd.containerName} has stale data mount '$currentDataPath' (expected '$expectedDataPath') — removing for recreate")
            runCatching { withContext(Dispatchers.IO) { containerManager.removeContainer(cmd.containerName, force = true) } }
                .onFailure { log.error("Failed to remove stale container ${cmd.containerName}", it) }
            out.serverStatus(cmd.serverId, ServerStatusUpdate.ServerStatus.UNHEALTHY)
            return
        }
        runCatching { containerManager.startContainer(cmd.containerName) }
            .onSuccess {
                out.serverStatus(cmd.serverId, ServerStatusUpdate.ServerStatus.HEALTHY)
            }
            .onFailure {
                log.error("Failed to start container ${cmd.containerName}", it)
                out.serverStatus(cmd.serverId, ServerStatusUpdate.ServerStatus.UNHEALTHY)
            }
    }

    suspend fun handleStop(cmd: StopContainerCommand, out: AgentOutbound) {
        log.info("Stopping container ${cmd.containerName}")
        runCatching { containerManager.stopContainer(cmd.containerName, cmd.timeoutSeconds, cmd.stopCommand) }
            .onSuccess {
                out.serverStatus(cmd.serverId, ServerStatusUpdate.ServerStatus.STOPPED)
            }
            .onFailure {
                log.error("Failed to stop container ${cmd.containerName}", it)
                out.serverStatus(cmd.serverId, ServerStatusUpdate.ServerStatus.UNHEALTHY)
            }
    }

    suspend fun handleRestart(cmd: RestartContainerCommand, out: AgentOutbound) {
        log.info("Restarting container ${cmd.containerName}")
        runCatching {
            containerManager.stopContainer(cmd.containerName, cmd.timeoutSeconds, cmd.stopCommand)
            containerManager.startContainer(cmd.containerName)
        }
            .onSuccess {
                out.serverStatus(cmd.serverId, ServerStatusUpdate.ServerStatus.HEALTHY)
            }
            .onFailure {
                log.error("Failed to restart container ${cmd.containerName}", it)
                out.serverStatus(cmd.serverId, ServerStatusUpdate.ServerStatus.UNHEALTHY)
            }
    }

    suspend fun handleRemove(cmd: RemoveContainerCommand, out: AgentOutbound) {
        log.info("Removing container ${cmd.containerName} (force=${cmd.force})")
        runCatching { containerManager.removeContainer(cmd.containerName, cmd.force) }
            .onSuccess {
                // Signal removal completion so master can sequence cross-node relocation
                // (target create must wait until the source container name is freed).
                out.serverStatus(cmd.serverId, ServerStatusUpdate.ServerStatus.STOPPED)
            }
            .onFailure {
                log.error("Failed to remove container ${cmd.containerName}", it)
                out.serverStatus(cmd.serverId, ServerStatusUpdate.ServerStatus.UNHEALTHY)
            }
    }

    suspend fun handlePullImage(cmd: PullImageCommand) {
        log.info("Pulling image ${cmd.image} for server ${cmd.serverId}")
        runCatching { withContext(Dispatchers.IO) { containerManager.pullImage(cmd.image) } }
            .onFailure { log.error("Failed to pull image ${cmd.image}", it) }
    }

    suspend fun handleShutdown(cmd: ShutdownCommand, out: AgentOutbound) {
        log.info("Shutdown requested — stopping all containers gracefully")
        val (graceful, forced) = containerManager.shutdownAll(cmd.timeoutSeconds)
        out.send {
            shutdownAcknowledge = shutdownAcknowledgeUpdate {
                gracefulCount = graceful
                forcedCount = forced
            }
        }
    }
}
