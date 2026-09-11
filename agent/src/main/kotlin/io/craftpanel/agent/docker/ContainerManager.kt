package io.craftpanel.agent.docker

import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.Frame
import io.craftpanel.proto.ContainerState
import io.craftpanel.proto.StartContainerCommand
import java.io.InputStream

/**
 * Container operations against the node's Docker daemon, with death-gating built in:
 * stop/kill/remove mark the resulting death intentional and start registers ownership,
 * so the [ContainerEventWatcher] never reports a death this agent caused.
 */
interface ContainerManager {

    fun listRunningContainerIds(): List<Pair<String, String>>

    fun listContainers(): List<ContainerState>

    fun createContainer(cmd: StartContainerCommand): String

    fun containerExists(containerName: String): Boolean

    fun pullImage(image: String)

    fun startContainer(containerName: String)

    fun stopContainer(containerName: String, timeoutSeconds: Int, stopCommand: String)

    fun killContainer(containerName: String)

    fun removeContainer(containerName: String, force: Boolean)

    fun getContainerNetworkNames(containerName: String): List<String>

    fun getContainerId(containerName: String): String?

    fun execRconCommand(serverId: String, command: String)

    fun isSwarmActive(): Boolean

    fun attachInteractive(
        containerName: String,
        inputStream: InputStream,
        callback: ResultCallback<Frame>
    ): ResultCallback<Frame>

    fun fetchLogs(containerName: String, tailLines: Int, callback: ResultCallback<Frame>): ResultCallback<Frame>

    fun shutdownAll(timeoutSeconds: Int): Pair<Int, Int>
}
