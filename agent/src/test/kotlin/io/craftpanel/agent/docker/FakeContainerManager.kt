package io.craftpanel.agent.docker

import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.exception.ConflictException
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

    /** Every command passed to [createContainer], in order (for asserting mounts/spec passthrough). */
    val createdCommands = CopyOnWriteArrayList<StartContainerCommand>()

    /** When true, [stopContainer] throws after marking stopping (graceful-stop failure injection). */
    var failStop = false

    /** When true, [startContainer] throws before starting (start failure injection). */
    var failStart = false

    /** When > 0, [stopContainer] blocks this many millis first (simulates a hanging graceful stop). */
    var stopBlockMs: Long = 0

    var swarmActive = false

    private fun serverIdOf(containerName: String): String = containerName.removePrefix("$containerNamePrefix-")

    private fun idOf(containerName: String) = "id-$containerName"

    private fun require(containerName: String): Entry =
        containers[containerName] ?: throw NotFoundException("no such container: $containerName")

    override fun createContainer(cmd: StartContainerCommand): String {
        calls.add("create:${cmd.containerName}")
        createdCommands.add(cmd)
        containers[cmd.containerName] = Entry(cmd.serverId, State.CREATED).also {
            if (cmd.dockerNetwork.isNotEmpty()) it.networks.add(cmd.dockerNetwork)
        }
        return idOf(cmd.containerName)
    }

    override fun containerExists(containerName: String): Boolean {
        calls.add("exists:$containerName")
        return containers.containsKey(containerName)
    }

    override fun isRunning(containerName: String): Boolean {
        calls.add("isRunning:$containerName")
        return containers[containerName]?.state == State.RUNNING
    }

    override fun pullImage(image: String) {
        calls.add("pull:$image")
    }

    override fun startContainer(containerName: String) {
        calls.add("start:$containerName")
        if (failStart) throw RuntimeException("injected start failure")
        require(containerName).state = State.RUNNING
        gate.markStarted(serverIdOf(containerName))
    }

    override fun stopContainer(containerName: String, timeoutSeconds: Int, stopCommand: String) {
        calls.add("stop:$containerName")
        gate.markStopping(serverIdOf(containerName))
        if (stopBlockMs > 0) Thread.sleep(stopBlockMs)
        containers[containerName]?.let { it.state = State.STOPPED }
        if (failStop) throw RuntimeException("injected stop failure")
    }

    override fun killContainer(containerName: String) {
        calls.add("kill:$containerName")
        val entry = containers[containerName] ?: return
        try {
            if (entry.state == State.STOPPED) {
                throw ConflictException("cannot kill container: $containerName: container is not running")
            }
            gate.markStopping(serverIdOf(containerName))
            entry.state = State.STOPPED
        } catch (_: ConflictException) {
            // Container already stopped — matches DockerContainerManager behavior.
            // ConflictException caught internally so withStatus in handleStop sees success.
        }
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

    /**
     * Reconstructs the container's config from the command it was created with, mirroring
     * [DockerContainerManager.createContainer] (env, bind, port bindings, user, limits, labels).
     */
    override fun inspectContainer(containerName: String): ContainerSnapshot? {
        calls.add("inspect:$containerName")
        if (!containers.containsKey(containerName)) return null
        val cmd = createdCommands.lastOrNull { it.containerName == containerName } ?: return null
        val portBindings = buildList {
            if (cmd.hostPort > 0) {
                add(PortBindingSnapshot(cmd.internalListenPort, cmd.containerProtocol.ifEmpty { "TCP" }.lowercase(), cmd.hostPort))
            }
            cmd.extraPortsList.forEach {
                if (it.hostPort > 0) add(PortBindingSnapshot(it.containerPort, it.protocol.ifEmpty { "TCP" }.lowercase(), it.hostPort))
            }
        }
        val labels = buildMap {
            put("craftpanel.managed", "true")
            put("craftpanel.server.id", cmd.serverId)
            if (cmd.publicHostname.isNotEmpty() && cmd.containerProtocol.uppercase() != "UDP") {
                put("mc-router.host", cmd.publicHostname)
            }
            if (cmd.stopCommand.isNotEmpty()) put("craftpanel.stop.command", cmd.stopCommand)
        }
        return ContainerSnapshot(
            image = cmd.image,
            env = cmd.envVarsMap.toMap(),
            binds = cmd.mountsList.map { BindSnapshot(it.hostPath, it.containerPath, it.readOnly) },
            portBindings = portBindings,
            user = cmd.containerUser,
            memoryMb = cmd.memoryMb,
            cpuShares = cmd.cpuShares,
            labels = labels,
            networkMode = cmd.dockerNetwork,
        )
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
