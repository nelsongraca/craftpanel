package io.craftpanel.agent.docker

import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.Frame
import io.craftpanel.proto.ContainerState
import io.craftpanel.proto.StartContainerCommand
import java.io.InputStream

/**
 * A read-only view of a container's actual on-node state, reconstructed from `docker inspect`.
 * The desired-state layer only needs presence and run state: every start recreates, so the live
 * container's configuration never needs comparing against the spec.
 */
data class ContainerSnapshot(
    /** Whether the container is currently running (`State.Running`). */
    val running: Boolean = false
)

/**
 * Minimal identity of a running managed container, taken from a single Docker list call.
 */
data class RunningContainer(val serverId: String, val containerId: String)

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

    /**
     * Best-effort pre-pull of [image] (unconditional; Docker no-ops when the tag has not moved).
     * Distinct from [pullImage], whose build-age gate is the wrong throttle for a refresh cadence.
     */
    fun refreshImage(image: String)

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
