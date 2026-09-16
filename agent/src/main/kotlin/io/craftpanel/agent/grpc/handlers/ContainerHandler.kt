package io.craftpanel.agent.grpc.handlers

import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.docker.NetworkManager
import io.craftpanel.agent.grpc.AgentOutbound
import io.craftpanel.common.ContainerNames
import io.craftpanel.proto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files

class ContainerHandler(private val containerManager: ContainerManager, private val config: AgentConfig, private val networkManager: NetworkManager) {

    private val log = LoggerFactory.getLogger(ContainerHandler::class.java)
    private val names = ContainerNames(config.containerNamePrefix)

    /**
     * start/stop/restart now live in the desired-state convergence layer
     * ([io.craftpanel.agent.desired.ConvergenceLoop] via [DesiredStateHandler]) — handled there so
     * crash-restart, budget, and status reporting are unified. This handler keeps the non-convergent
     * lifecycle ops: permanent removal, shutdown, and symlink rebuild.
     */

    suspend fun handleRemove(cmd: RemoveContainerCommand, out: AgentOutbound) {
        log.info("Removing container ${cmd.containerName} (force=${cmd.force})")
        val networkNames = withContext(Dispatchers.IO) { containerManager.getContainerNetworkNames(cmd.containerName) }
        val containerId = withContext(Dispatchers.IO) { containerManager.getContainerId(cmd.containerName) }
        withStatus(out, cmd.serverId, ServerStatusUpdate.ServerStatus.STOPPED, log, "Failed to remove container ${cmd.containerName}") {
            containerManager.removeContainer(cmd.containerName, cmd.force)
            withContext(Dispatchers.IO) {
                // Clean up from container's network list (if container existed)
                if (containerId != null) {
                    networkNames
                        .filter { names.isManagedNetwork(it) }
                        .forEach { net -> networkManager.maybeDetachAndDelete(net, containerId) }
                }
                // Always try deterministic cleanup of standalone server bridge by server ID
                // (handles the case where the container was already gone before this command)
                networkManager.maybeDetachAndDelete(
                    names.standaloneNetwork(cmd.serverId),
                    ""
                )
            }
            if (cmd.deleteData) {
                withContext(Dispatchers.IO) { deleteServerData(cmd.serverId) }
                runCatching {
                    SymlinkMaintainer.removeServerNameSymlink(config.serversByNameRoot, cmd.serverName)
                }.onFailure { log.warn("Failed to remove servers-by-name symlink for ${cmd.serverId}", it) }
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

    /** Recreates the servers-by-name symlink tree from a master snapshot (reconnect self-heal). */
    suspend fun rebuildServerSymlinks(servers: List<RebuildSymlinksCommand.ServerEntry>) {
        withContext(Dispatchers.IO) {
            servers.forEach { entry ->
                runCatching {
                    val canonicalPath = serverDataRoot(config.dataBasePath, entry.serverId)
                    if (Files.exists(canonicalPath)) {
                        SymlinkMaintainer.createServerNameSymlink(
                            serversByNameRoot = config.serversByNameRoot,
                            name = entry.serverName,
                            canonicalPath = canonicalPath
                        )
                    }
                }.onFailure { log.warn("Rebuild: failed servers-by-name symlink for ${entry.serverId}", it) }
            }
        }
    }
}
