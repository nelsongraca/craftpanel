package io.craftpanel.agent.desired

import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.docker.NetworkManager
import io.craftpanel.agent.grpc.handlers.SymlinkMaintainer
import io.craftpanel.agent.grpc.handlers.serverDataRoot
import io.craftpanel.proto.StartContainerCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files

/**
 * Docker-executing half of the convergence machine: turns a [ConvergenceDecision] into docker
 * calls. All container mechanics (create-if-absent / recreate / pull / mount / symlink / start /
 * stop / kill) live here so the loop stays a thin orchestrator and both the legacy one-shot paths
 * and the desired-state envelopes share the same executor.
 *
 * Stop parameters come from the spec's stop_command plus a default timeout (mirror of master's
 * stop default); legacy commands may override them explicitly. Observe that this operator must
 * always be entered through a [ConvergenceLoop] so the WatcherGate invariant (authored deaths are
 * never reported as crashes) is preserved — the gate lives inside [ContainerManager].
 */
class ContainerOperator(
    private val containerManager: ContainerManager,
    private val networkManager: NetworkManager,
    private val config: AgentConfig,
) {

    private val log = LoggerFactory.getLogger(ContainerOperator::class.java)

    fun containerExists(containerName: String): Boolean = containerManager.containerExists(containerName)

    fun isRunning(containerName: String): Boolean = containerManager.isRunning(containerName)

    /**
     * Brings the container to running against [spec]. When [recreate] is true (the agent detected
     * the stored spec differs from the one the container was last applied with) the existing
     * container is removed first; otherwise an existing container is simply started, and a missing
     * one is created. Returns true when the container was actually created/recreated — the caller
     * uses this to record [io.craftpanel.agent.desired.DesiredState.appliedSpec] (a plain start of
     * an existing container must NOT claim the new spec was applied). Throws on failure so the
     * caller reports UNHEALTHY / retries.
     */
    suspend fun ensureRunning(spec: StartContainerCommand, recreate: Boolean): Boolean {
        val containerName = spec.containerName
        val exists = withContext(Dispatchers.IO) { containerManager.containerExists(containerName) }
        val needsCreate = recreate || !exists
        log.info("Converge: start container $containerName (recreate=$recreate, needsCreate=$needsCreate)")
        if (needsCreate) {
            if (exists) {
                withContext(Dispatchers.IO) { containerManager.removeContainer(containerName, force = true) }
            }
            withContext(Dispatchers.IO) { containerManager.pullImage(spec.image) }
            val specWithMount = spec.toBuilder()
                .addMounts(
                    io.craftpanel.proto.volumeMount {
                        hostPath = "${config.hostDataBasePath}/servers/${spec.serverId}"
                        containerPath = spec.dataContainerPath.ifEmpty { "/data" }
                        readOnly = false
                    }
                )
                .build()
            val dockerNetwork = spec.dockerNetwork
            if (dockerNetwork.isNotEmpty()) {
                withContext(Dispatchers.IO) { networkManager.ensureNetwork(dockerNetwork) }
            }
            withContext(Dispatchers.IO) { containerManager.createContainer(specWithMount) }
            if (dockerNetwork.isNotEmpty()) {
                withContext(Dispatchers.IO) { networkManager.attachToNetwork(dockerNetwork) }
            }
        }
        runCatching {
            val canonicalRoot = serverDataRoot(config.dataBasePath, spec.serverId)
            Files.createDirectories(canonicalRoot)
            SymlinkMaintainer.createServerNameSymlink(
                serversByNameRoot = config.serversByNameRoot,
                name = spec.serverName,
                canonicalPath = canonicalRoot
            )
        }.onFailure { log.warn("Failed to create servers-by-name symlink for ${spec.serverId}", it) }
        withContext(Dispatchers.IO) { containerManager.startContainer(containerName) }
        return needsCreate
    }

    /** Graceful stop (signal/stdin stop command then Docker stop with [timeoutSeconds] timeout). */
    suspend fun ensureStopped(containerName: String, timeoutSeconds: Int = DEFAULT_STOP_TIMEOUT_SECONDS, stopCommand: String = "") {
        withContext(Dispatchers.IO) { containerManager.stopContainer(containerName, timeoutSeconds, stopCommand) }
    }

    /** Immediate SIGKILL. */
    suspend fun forceKill(containerName: String) {
        withContext(Dispatchers.IO) { containerManager.killContainer(containerName) }
    }

    companion object {
        /** Mirrors master's default stop timeout (ContainerLifecycle.stopTimeoutSeconds). */
        const val DEFAULT_STOP_TIMEOUT_SECONDS = 45
    }
}