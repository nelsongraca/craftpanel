package io.craftpanel.agent.docker

import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.Frame
import io.craftpanel.proto.ContainerState
import io.craftpanel.proto.StartContainerCommand
import java.io.InputStream

/**
 * A read-only view of a container's actual configuration, reconstructed from `docker inspect`.
 * The desired-state layer compares this against the desired [StartContainerCommand] to decide a
 * recreate — so the decision is based on the live container, not only on the agent's in-memory
 * applied spec (which is lost when the agent process restarts).
 */
data class ContainerSnapshot(
    val image: String,
    /** Effective env (image defaults + configured), parsed from `KEY=VALUE` pairs. */
    val env: Map<String, String>,
    val binds: List<BindSnapshot>,
    val portBindings: List<PortBindingSnapshot>,
    val user: String,
    val memoryMb: Int,
    /** Hard CPU cap in millicores (0 = unlimited), from Docker `NanoCpus` (or quota/period). */
    val cpuLimitMillicores: Int,
    val labels: Map<String, String>,
    val networkMode: String,
    /** Docker hostname (`Config.Hostname`) — the server name, and thus its DNS name on the network. */
    val hostname: String,
    /** Whether the container is currently running (`State.Running`). */
    val running: Boolean = false
)

data class BindSnapshot(val hostPath: String, val containerPath: String, val readOnly: Boolean)

data class PortBindingSnapshot(val containerPort: Int, val protocol: String, val hostPort: Int)

/**
 * Minimal identity of a running managed container, taken from a single Docker list call.
 * [routingHost] is the first non-blank entry of the container's `mc-router.host` label, used as
 * the ping target for player-count collection without a second inspect round-trip.
 */
data class RunningContainer(val serverId: String, val containerId: String, val routingHost: String?)

/**
 * Container operations against the node's Docker daemon, with death-gating built in:
 * stop/kill/remove mark the resulting death intentional and start registers ownership,
 * so the [ContainerEventWatcher] never reports a death this agent caused.
 */
interface ContainerManager {

    fun listRunningContainers(): List<RunningContainer>

    /** Convenience projection for callers that only need the server/container identity. */
    fun listRunningContainerIds(): List<Pair<String, String>> = listRunningContainers().map { it.serverId to it.containerId }

    fun listContainers(): List<ContainerState>

    fun createContainer(cmd: StartContainerCommand): String

    fun containerExists(containerName: String): Boolean

    /** True iff the container exists AND its state is running. */
    fun isRunning(containerName: String): Boolean

    fun pullImage(image: String)

    fun startContainer(containerName: String)

    fun stopContainer(containerName: String, timeoutSeconds: Int, stopCommand: String)

    fun killContainer(containerName: String)

    fun removeContainer(containerName: String, force: Boolean)

    fun getContainerNetworkNames(containerName: String): List<String>

    fun getContainerId(containerName: String): String?

    /** Inspect the container's actual configuration, or null when it does not exist. */
    fun inspectContainer(containerName: String): ContainerSnapshot?

    fun execRconCommand(serverId: String, command: String)

    fun isSwarmActive(): Boolean

    fun attachInteractive(containerName: String, inputStream: InputStream, callback: ResultCallback<Frame>): ResultCallback<Frame>

    fun fetchLogs(containerName: String, tailLines: Int, callback: ResultCallback<Frame>): ResultCallback<Frame>

    fun shutdownAll(timeoutSeconds: Int): Pair<Int, Int>

    companion object {
        /** Default graceful-stop timeout, mirrored from master's `ContainerLifecycle.stopTimeout`. */
        const val DEFAULT_STOP_TIMEOUT_SECONDS = 45
    }
}
