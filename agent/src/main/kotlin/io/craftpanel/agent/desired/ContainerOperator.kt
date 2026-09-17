package io.craftpanel.agent.desired

import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.BindSnapshot
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.docker.ContainerSnapshot
import io.craftpanel.agent.docker.ContainerSpecDiff
import io.craftpanel.agent.docker.NetworkManager
import io.craftpanel.agent.docker.PortBindingSnapshot
import io.craftpanel.agent.docker.SpecDiff
import io.craftpanel.agent.grpc.handlers.ServerDataDirs
import io.craftpanel.agent.grpc.handlers.SymlinkMaintainer
import io.craftpanel.agent.grpc.handlers.serverDataRoot
import io.craftpanel.common.ServerPaths
import io.craftpanel.proto.StartContainerCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files

/**
 * Docker-executing half of the convergence machine: turns a [ConvergenceDecision] into docker
 * calls. All container mechanics (create-if-absent / recreate / pull / mount / symlink / start /
 * stop / kill) live here so the loop stays a thin orchestrator.
 *
 * Stop parameters come from the spec's stop_command plus a default timeout (mirror of master's
 * stop default). Observe that this operator must always be entered through a [ConvergenceLoop] so
 * the WatcherGate invariant (authored deaths are never reported as crashes) is preserved — the gate
 * lives inside [ContainerManager].
 */
class ContainerOperator(
    private val containerManager: ContainerManager,
    private val networkManager: NetworkManager,
    private val config: AgentConfig,
    /** Bounded retry budget for a transient host-port conflict at start (see [startWithPortConflictRetry]). */
    private val startConflictMaxAttempts: Int = 12,
    private val startConflictRetryDelayMs: Long = 5_000L
) {

    private val log = LoggerFactory.getLogger(ContainerOperator::class.java)

    fun containerExists(containerName: String): Boolean = containerManager.containerExists(containerName)

    fun isRunning(containerName: String): Boolean = containerManager.isRunning(containerName)

    /** Inspect the live container's config, or null when it does not exist. */
    fun inspect(containerName: String): ContainerSnapshot? = containerManager.inspectContainer(containerName)

    /**
     * Whether the live container's configuration already satisfies [spec] — see [ContainerSpecDiff].
     * Any mismatch means we are certain the container differs and must be recreated.
     */
    fun diff(snapshot: ContainerSnapshot, spec: StartContainerCommand): SpecDiff = ContainerSpecDiff.diff(spec, snapshot, config.hostDataBasePath)

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
        // Keep the path registry in step with the spec so file/backup ops resolve the same
        // directory this container is mounted against.
        ServerDataDirs.put(spec.serverId, spec.dataDirName)
        val dataDir = ServerPaths.dataDir(config.hostDataBasePath, spec.serverId, spec.dataDirName)
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
                        hostPath = dataDir
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
        withContext(Dispatchers.IO) { startWithPortConflictRetry(containerName) }
        return needsCreate
    }

    /**
     * Starts the container, retrying while Docker reports its host port is still bound. Deleting a
     * server frees its port in master's registry immediately, but the agent may not have finished
     * removing the old container yet — so a freshly created server can be handed a port that is
     * briefly still in use. The conflict clears once the old container is gone, so a bounded retry
     * rides it out instead of leaving the new server permanently UNHEALTHY.
     */
    private suspend fun startWithPortConflictRetry(containerName: String) {
        var attempt = 1
        while (true) {
            val failure = runCatching { containerManager.startContainer(containerName) }.exceptionOrNull()
                ?: return
            if (!failure.isHostPortConflict() || attempt >= startConflictMaxAttempts) throw failure
            log.warn(
                "Start of {} failed — host port still in use ({}); retry {}/{} in {}ms",
                containerName, failure.message, attempt, startConflictMaxAttempts, startConflictRetryDelayMs
            )
            delay(startConflictRetryDelayMs)
            attempt++
        }
    }

    private fun Throwable.isHostPortConflict(): Boolean {
        val text = message?.lowercase() ?: return false
        return "address already in use" in text ||
            "port is already allocated" in text ||
            "failed to bind host port" in text
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
