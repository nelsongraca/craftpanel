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

    suspend fun handleStart(cmd: StartContainerCommand, out: AgentOutbound) {
        val needsCreate = cmd.needsRecreate || !withContext(Dispatchers.IO) { containerManager.containerExists(cmd.containerName) }
        log.info("Starting container ${cmd.containerName} (needsRecreate=${cmd.needsRecreate}, needsCreate=$needsCreate)")
        withStatus(out, cmd.serverId, ServerStatusUpdate.ServerStatus.HEALTHY, log, "Failed to start container ${cmd.containerName}") {
            if (needsCreate) {
                if (withContext(Dispatchers.IO) { containerManager.containerExists(cmd.containerName) }) {
                    withContext(Dispatchers.IO) { containerManager.removeContainer(cmd.containerName, force = true) }
                }
                withContext(Dispatchers.IO) { containerManager.pullImage(cmd.image) }
                val cmdWithMount = cmd.toBuilder()
                    .addMounts(volumeMount {
                        hostPath = "${config.hostDataBasePath}/servers/${cmd.serverId}"
                        containerPath = "/data"
                        readOnly = false
                    })
                    .build()
                withContext(Dispatchers.IO) { containerManager.createContainer(cmdWithMount) }
            }
            containerManager.startContainer(cmd.containerName)
        }
    }

    suspend fun handleStop(cmd: StopContainerCommand, out: AgentOutbound) {
        log.info("Stopping container ${cmd.containerName}")
        withStatus(out, cmd.serverId, ServerStatusUpdate.ServerStatus.STOPPED, log, "Failed to stop container ${cmd.containerName}") {
            containerManager.stopContainer(cmd.containerName, cmd.timeoutSeconds, cmd.stopCommand)
        }
    }

    suspend fun handleRestart(cmd: RestartContainerCommand, out: AgentOutbound) {
        log.info("Restarting container ${cmd.containerName}")
        withStatus(out, cmd.serverId, ServerStatusUpdate.ServerStatus.HEALTHY, log, "Failed to restart container ${cmd.containerName}") {
            containerManager.stopContainer(cmd.containerName, cmd.timeoutSeconds, cmd.stopCommand)
            containerManager.startContainer(cmd.containerName)
        }
    }

    suspend fun handleRemove(cmd: RemoveContainerCommand, out: AgentOutbound) {
        log.info("Removing container ${cmd.containerName} (force=${cmd.force})")
        // Signal removal completion so master can sequence cross-node relocation
        // (target create must wait until the source container name is freed).
        withStatus(out, cmd.serverId, ServerStatusUpdate.ServerStatus.STOPPED, log, "Failed to remove container ${cmd.containerName}") {
            containerManager.removeContainer(cmd.containerName, cmd.force)
        }
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
