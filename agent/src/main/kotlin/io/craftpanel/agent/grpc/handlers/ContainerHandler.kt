package io.craftpanel.agent.grpc.handlers

import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.docker.NetworkManager
import io.craftpanel.agent.grpc.AgentOutbound
import io.craftpanel.proto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files

class ContainerHandler(private val containerManager: ContainerManager, private val config: AgentConfig, private val networkManager: NetworkManager) {

    private val log = LoggerFactory.getLogger(ContainerHandler::class.java)

    suspend fun handleStart(cmd: StartContainerCommand, out: AgentOutbound) {
        val needsCreate = cmd.needsRecreate || !withContext(Dispatchers.IO) { containerManager.containerExists(cmd.containerName) }
        log.info("Starting container ${cmd.containerName} (needsRecreate=${cmd.needsRecreate}, needsCreate=$needsCreate)")
        withStatus(out, cmd.serverId, ServerStatusUpdate.ServerStatus.HEALTHY, log, "Failed to start container ${cmd.containerName}") {
            if (needsCreate) {
                if (withContext(Dispatchers.IO) { containerManager.containerExists(cmd.containerName) }) {
                    // Intentional removal: suppress the resulting die event until start succeeds.
                    containerManager.markStopping(cmd.serverId)
                    withContext(Dispatchers.IO) { containerManager.removeContainer(cmd.containerName, force = true) }
                }
                withContext(Dispatchers.IO) { containerManager.pullImage(cmd.image) }
                val cmdWithMount = cmd.toBuilder()
                    .addMounts(
                        volumeMount {
                            hostPath = "${config.hostDataBasePath}/servers/${cmd.serverId}"
                            containerPath = "/data"
                            readOnly = false
                        }
                    )
                    .build()
                val dockerNetwork = cmd.dockerNetwork
                if (dockerNetwork.isNotEmpty()) {
                    withContext(Dispatchers.IO) { networkManager.ensureNetwork(dockerNetwork) }
                }
                withContext(Dispatchers.IO) { containerManager.createContainer(cmdWithMount) }
                if (dockerNetwork.isNotEmpty()) {
                    withContext(Dispatchers.IO) { networkManager.attachToNetwork(dockerNetwork) }
                }
            }
            containerManager.startContainer(cmd.containerName)
            // Mark started after startContainer succeeds: clears any stopping flag and registers
            // this agent as owner so future unexpected deaths are reported.
            containerManager.markStarted(cmd.serverId)
        }
    }

    suspend fun handleStop(cmd: StopContainerCommand, out: AgentOutbound) {
        log.info("Stopping container ${cmd.containerName}")
        containerManager.markStopping(cmd.serverId)
        withStatus(out, cmd.serverId, ServerStatusUpdate.ServerStatus.STOPPED, log, "Failed to stop container ${cmd.containerName}") {
            containerManager.stopContainer(cmd.containerName, cmd.timeoutSeconds, cmd.stopCommand)
        }
    }

    suspend fun handleRestart(cmd: RestartContainerCommand, out: AgentOutbound) {
        log.info("Restarting container ${cmd.containerName}")
        containerManager.markStopping(cmd.serverId)
        withStatus(out, cmd.serverId, ServerStatusUpdate.ServerStatus.HEALTHY, log, "Failed to restart container ${cmd.containerName}") {
            containerManager.stopContainer(cmd.containerName, cmd.timeoutSeconds, cmd.stopCommand)
            containerManager.startContainer(cmd.containerName)
            containerManager.markStarted(cmd.serverId)
        }
    }

    suspend fun handleRemove(cmd: RemoveContainerCommand, out: AgentOutbound) {
        log.info("Removing container ${cmd.containerName} (force=${cmd.force})")
        // Signal removal completion so master can sequence cross-node relocation
        // (target create must wait until the source container name is freed).
        containerManager.markStopping(cmd.serverId)
        val networkNames = withContext(Dispatchers.IO) { containerManager.getContainerNetworkNames(cmd.containerName) }
        val containerId = withContext(Dispatchers.IO) { containerManager.getContainerId(cmd.containerName) }
        withStatus(out, cmd.serverId, ServerStatusUpdate.ServerStatus.STOPPED, log, "Failed to remove container ${cmd.containerName}") {
            containerManager.removeContainer(cmd.containerName, cmd.force)
            containerManager.markRemoved(cmd.serverId)
            withContext(Dispatchers.IO) {
                // Clean up from container's network list (if container existed)
                if (containerId != null) {
                    networkNames
                        .filter { it.startsWith("craftpanel-net-") || it.startsWith("craftpanel-server-") }
                        .forEach { net -> networkManager.maybeDetachAndDelete(net, containerId) }
                }
                // Always try deterministic cleanup of standalone server bridge by server ID
                // (handles the case where the container was already gone before this command)
                networkManager.maybeDetachAndDelete(
                    "${config.containerNamePrefix}-server-${cmd.serverId}",
                    ""
                )
            }
            if (cmd.deleteData) {
                withContext(Dispatchers.IO) { deleteServerData(cmd.serverId) }
            }
        }
    }

    /** Permanently removes the server's data directory. Only called when [RemoveContainerCommand.deleteData] is set. */
    private fun deleteServerData(serverId: String) {
        val root = serverDataRoot(config.dataBasePath, serverId)
        if (!Files.exists(root)) return
        log.info("Deleting server data directory $root")
        deleteRecursively(root)
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
