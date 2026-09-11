package io.craftpanel.agent.docker

import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.exception.NotFoundException
import com.github.dockerjava.api.model.Frame
import io.craftpanel.proto.*
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap

/**
 * Behavioral in-memory [ContainerManager] for handler tests: models the container state
 * machine and wires a real [WatcherGate], so tests assert outcomes ("the die is suppressed")
 * instead of call shapes.
 *
 * Semantics mirrored from [DockerContainerManager] (keep in sync — auditable at a glance):
 *
 * | operation        | absent container            | present container                     | gate                          |
 * |------------------|-----------------------------|---------------------------------------|-------------------------------|
 * | createContainer  | registers CREATED           | replaces entry as CREATED             | —                             |
 * | startContainer   | throws NotFoundException    | CREATED/STOPPED → RUNNING             | markStarted                   |
 * | stopContainer    | no-op                       | → STOPPED (throws if [failStop])      | markStopping (always)         |
 * | killContainer    | no-op                       | → STOPPED                             | markStopping (always)         |
 * | removeContainer  | no-op                       | entry gone                            | markStopping + markRemoved    |
 * | containerExists  | false                       | true                                  | —                             |
 */
class FakeContainerManager(
    private val containerNamePrefix: String = "craftpanel",
) : ContainerManager {

    enum class State { CREATED, RUNNING, STOPPED }

    class Entry(val serverId: String, var state: State, val networks: MutableSet<String> = mutableSetOf())

    val gate = WatcherGate()
    val calls = CopyOnWriteArrayList<String>()
    val containers = ConcurrentHashMap<String, Entry>()

    /** When true, [stopContainer] throws after marking stopping (graceful-stop failure injection). */
    var failStop = false
    var swarmActive = false

    private fun serverIdOf(containerName: String): String = containerName.removePrefix("$containerNamePrefix-")

    private fun idOf(containerName: String) = "id-$containerName"

    private fun require(containerName: String): Entry =
        containers[containerName] ?: throw NotFoundException("no such container: $containerName")

    override fun createContainer(cmd: StartContainerCommand): String {
        calls.add("create:${cmd.containerName}")
        containers[cmd.containerName] = Entry(cmd.serverId, State.CREATED).also {
            if (cmd.dockerNetwork.isNotEmpty()) it.networks.add(cmd.dockerNetwork)
        }
        return idOf(cmd.containerName)
    }

    override fun containerExists(containerName: String): Boolean {
        calls.add("exists:$containerName")
        return containers.containsKey(containerName)
    }

    override fun pullImage(image: String) {
        calls.add("pull:$image")
    }

    override fun startContainer(containerName: String) {
        calls.add("start:$containerName")
        require(containerName).state = State.RUNNING
        gate.markStarted(serverIdOf(containerName))
    }

    override fun stopContainer(containerName: String, timeoutSeconds: Int, stopCommand: String) {
        calls.add("stop:$containerName")
        gate.markStopping(serverIdOf(containerName))
        containers[containerName]?.let { it.state = State.STOPPED }
        if (failStop) throw RuntimeException("injected stop failure")
    }

    override fun killContainer(containerName: String) {
        calls.add("kill:$containerName")
        gate.markStopping(serverIdOf(containerName))
        containers[containerName]?.let { it.state = State.STOPPED }
    }

    override fun removeContainer(containerName: String, force: Boolean) {
        calls.add("remove:$containerName")
        gate.markStopping(serverIdOf(containerName))
        containers.remove(containerName)
        gate.markRemoved(serverIdOf(containerName))
    }

    override fun listRunningContainerIds(): List<Pair<String, String>> {
        calls.add("listRunningIds")
        return containers.entries
            .filter { it.value.state == State.RUNNING }
            .map { it.value.serverId to idOf(it.key) }
    }

    override fun listContainers(): List<ContainerState> {
        calls.add("listContainers")
        return containers.map { (name, entry) ->
            containerState {
                containerId = idOf(name)
                containerName = name
                serverId = entry.serverId
                runState = when (entry.state) {
                    State.RUNNING -> ContainerState.RunState.RUNNING
                    State.STOPPED -> ContainerState.RunState.STOPPED
                    State.CREATED -> ContainerState.RunState.EXITED
                }
            }
        }
    }

    override fun getContainerNetworkNames(containerName: String): List<String> {
        calls.add("networks:$containerName")
        return containers[containerName]?.networks?.toList() ?: emptyList()
    }

    override fun getContainerId(containerName: String): String? {
        calls.add("id:$containerName")
        return containers[containerName]?.let { idOf(containerName) }
    }

    override fun execRconCommand(serverId: String, command: String) {
        calls.add("rcon:$serverId:$command")
    }

    override fun isSwarmActive(): Boolean = swarmActive

    override fun attachInteractive(
        containerName: String,
        inputStream: InputStream,
        callback: ResultCallback<Frame>
    ): ResultCallback<Frame> {
        calls.add("attach:$containerName")
        return callback
    }

    override fun fetchLogs(containerName: String, tailLines: Int, callback: ResultCallback<Frame>): ResultCallback<Frame> {
        calls.add("logs:$containerName:$tailLines")
        return callback
    }

    override fun shutdownAll(timeoutSeconds: Int): Pair<Int, Int> {
        calls.add("shutdownAll")
        var graceful = 0
        var forced = 0
        for (name in containers.keys.toList()) {
            runCatching {
                stopContainer(name, timeoutSeconds, "")
                graceful++
            }.onFailure {
                killContainer(name)
                forced++
            }
        }
        return Pair(graceful, forced)
    }
}
