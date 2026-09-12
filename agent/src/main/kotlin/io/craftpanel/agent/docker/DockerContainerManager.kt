package io.craftpanel.agent.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.api.exception.NotFoundException
import com.github.dockerjava.api.model.*
import io.craftpanel.proto.*
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Docker-backed [ContainerManager]. Owns the death-gating invariant: every stop/kill/remove
 * marks the death intentional on the [WatcherGate] before the docker call, and every successful
 * start registers ownership — so a death this agent caused is never reported as a crash.
 *
 * Server ids are derived from container names: managed containers are always named
 * `$containerNamePrefix-$serverId` (master builds every command's containerName that way, and
 * [execRconCommand] already relied on the same convention).
 */
class DockerContainerManager(
    private val docker: DockerClient,
    private val gate: WatcherGate,
    private val craftpanelNetwork: String = "",
    private val containerNamePrefix: String = "craftpanel",
    private val pullMaxImageAgeHours: Long = 24
) : ContainerManager {

    private val log = LoggerFactory.getLogger(DockerContainerManager::class.java)

    private fun serverIdOf(containerName: String): String = containerName.removePrefix("$containerNamePrefix-")

    override fun listRunningContainerIds(): List<Pair<String, String>> {
        return docker.listContainersCmd()
            .withShowAll(false)
            .exec()
            .filter { it.labels.containsKey("craftpanel.server.id") }
            .mapNotNull { container ->
                val serverId = container.labels["craftpanel.server.id"]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                serverId to container.id
            }
    }

    override fun listContainers(): List<ContainerState> = docker.listContainersCmd()
        .withShowAll(true)
        .exec()
        .filter { it.names.any { n -> n.contains("$containerNamePrefix-") } }
        .map { container ->
            containerState {
                containerId = container.id
                containerName = container.names.firstOrNull()
                    ?.trimStart('/') ?: container.id
                serverId = container.labels["craftpanel.server.id"] ?: ""
                runState = when (container.state) {
                    "running" -> ContainerState.RunState.RUNNING
                    "exited" if container.status.contains("(0)") -> ContainerState.RunState.STOPPED
                    else -> ContainerState.RunState.EXITED
                }
            }
        }

    override fun createContainer(cmd: StartContainerCommand): String {
        val exposedPortsList = mutableListOf<ExposedPort>()
        val portBindings = Ports()

        val isUdp = cmd.containerProtocol.uppercase() == "UDP"
        val minecraftPort = if (isUdp) ExposedPort.udp(cmd.internalListenPort) else ExposedPort.tcp(cmd.internalListenPort)
        exposedPortsList.add(minecraftPort)
        if (cmd.hostPort > 0) {
            portBindings.bind(minecraftPort, Ports.Binding.bindPort(cmd.hostPort))
        }

        cmd.extraPortsList.forEach { extra ->
            val isExtraUdp = extra.protocol.uppercase() == "UDP"
            val extraPort = if (isExtraUdp) ExposedPort.udp(extra.containerPort) else ExposedPort.tcp(extra.containerPort)
            exposedPortsList.add(extraPort)
            if (extra.hostPort > 0) {
                portBindings.bind(extraPort, Ports.Binding.bindPort(extra.hostPort))
            }
        }

        val binds = cmd.mountsList.map { mount ->
            Bind(mount.hostPath, Volume(mount.containerPath), if (mount.readOnly) AccessMode.ro else AccessMode.rw)
        }

        val envList = cmd.envVarsMap.map { (k, v) -> "$k=$v" }

        val hostConfig = HostConfig.newHostConfig()
            .withPortBindings(portBindings)
            .withBinds(binds)
            // Restart is app-owned (master decides); Docker must not auto-restart managed containers.
            .withRestartPolicy(RestartPolicy.noRestart())
            .let { cfg ->
                if (cmd.memoryMb > 0) cfg.withMemory(cmd.memoryMb.toLong() * 1024 * 1024) else cfg
            }
            .let { cfg ->
                if (cmd.cpuShares > 0) cfg.withCpuShares(cmd.cpuShares) else cfg
            }
            .let { cfg ->
                if (cmd.dockerNetwork.isNotEmpty()) cfg.withNetworkMode(cmd.dockerNetwork) else cfg
            }

        val response = docker.createContainerCmd(cmd.image)
            .withName(cmd.containerName)
            .withEnv(envList)
            .withExposedPorts(exposedPortsList)
            .withHostConfig(hostConfig)
            .withLabels(
                buildMap {
                    put("craftpanel.managed", "true")
                    put("craftpanel.server.id", cmd.serverId)
                    if (cmd.publicHostname.isNotEmpty() && !isUdp) {
                        // mc-router auto-discovery labels (https://github.com/itzg/mc-router).
                        // `mc-router.host` is the routing hostname; `mc-router.port` is the
                        // container-internal Minecraft port; `mc-router.network` tells mc-router
                        // which Docker network to dial the backend on (the shared craftpanel
                        // network both mc-router and this container are attached to). UDP
                        // backends cannot be proxied by mc-router, so skip the labels.
                        put("mc-router.host", cmd.publicHostname)
                        put("mc-router.port", cmd.internalListenPort.toString())
                        if (craftpanelNetwork.isNotEmpty()) {
                            put("mc-router.network", craftpanelNetwork)
                        }
                    }
                    if (cmd.stopCommand.isNotEmpty()) {
                        put("craftpanel.stop.command", cmd.stopCommand)
                    }
                }
            )
            .withStdinOpen(true)
            .exec()

        if (craftpanelNetwork.isNotEmpty()) {
            runCatching {
                docker.connectToNetworkCmd()
                    .withNetworkId(craftpanelNetwork)
                    .withContainerId(response.id)
                    .exec()
            }.onFailure { log.warn("Could not connect ${cmd.containerName} to $craftpanelNetwork: ${it.message}") }
        }
        log.info("Created container ${cmd.containerName} (server ${cmd.serverId})")
        return response.id
    }

    override fun containerExists(containerName: String): Boolean = runCatching {
        docker.inspectContainerCmd(containerName)
            .exec()
        true
    }.getOrDefault(false)

    override fun pullImage(image: String) = pullImage(image, pullMaxImageAgeHours)

    private fun pullImage(image: String, maxAgeHours: Long) {
        val cachedAt = runCatching {
            docker.inspectImageCmd(image)
                .exec().created
        }.getOrNull()

        if (cachedAt != null) {
            val age = Duration.between(
                Instant.parse(cachedAt),
                Instant.now()
            )
            if (age.toHours() < maxAgeHours) {
                log.info("Skipping pull for $image — local image is ${age.toMinutes()}m old (max ${maxAgeHours}h)")
                return
            }
            log.info("Local image $image is ${age.toHours()}h old — pulling fresh copy")
        }

        runCatching {
            docker.pullImageCmd(image)
                .exec(PullImageResultCallback())
                .awaitCompletion()
            log.info("Pulled image $image")
        }.onFailure {
            log.warn("Failed to pull image $image — using local cache if available: ${it.message}")
        }
    }

    override fun startContainer(containerName: String) {
        docker.startContainerCmd(containerName)
            .exec()
        log.info("Started container $containerName")
        gate.markStarted(serverIdOf(containerName))
    }

    override fun stopContainer(containerName: String, timeoutSeconds: Int, stopCommand: String) {
        gate.markStopping(serverIdOf(containerName))
        val timeout = timeoutSeconds.takeIf { it > 0 } ?: 30

        if (stopCommand.isNotEmpty()) {
            val exited = sendStopCommandToStdin(containerName, stopCommand, timeout)
            if (exited) {
                log.info("Container {} exited cleanly after stop command", containerName)
                return
            }
            log.warn("Container {} did not exit within {}s after stop command — force stopping", containerName, timeout)
        }

        try {
            docker.stopContainerCmd(containerName)
                .withTimeout(if (stopCommand.isNotEmpty()) 5 else timeout)
                .exec()
            log.info("Stopped container {}", containerName)
        } catch (_: NotFoundException) {
            // Container already gone (e.g. server was never started) — stopping is
            // idempotent, the desired end state (not running) already holds.
            log.info("Container {} does not exist — treating stop as already-stopped", containerName)
        }
    }

    private fun sendStopCommandToStdin(containerName: String, command: String, timeoutSeconds: Int): Boolean = runCatching {
        val inputStream = ByteArrayInputStream("$command\n".toByteArray())

        docker.attachContainerCmd(containerName)
            .withStdIn(inputStream)
            .withStdOut(true)
            .withStdErr(true)
            .withFollowStream(true)
            .withLogs(true)
            .exec(ResultCallback.Adapter())
            .awaitCompletion(timeoutSeconds.toLong(), TimeUnit.SECONDS)

        docker.inspectContainerCmd(containerName)
            .exec().state?.running != true
    }.getOrElse { e ->
        log.warn("Failed to send stop command to container {}: {}", containerName, e.message)
        false
    }

    override fun killContainer(containerName: String) {
        gate.markStopping(serverIdOf(containerName))
        try {
            docker.killContainerCmd(containerName)
                .exec()
            log.info("Force-killed container {}", containerName)
        } catch (_: NotFoundException) {
            // Container already gone — force-kill is idempotent.
            log.info("Container {} does not exist — treating force-kill as already-stopped", containerName)
        }
    }

    override fun removeContainer(containerName: String, force: Boolean) {
        gate.markStopping(serverIdOf(containerName))
        try {
            docker.removeContainerCmd(containerName)
                .withForce(force)
                .exec()
            log.info("Removed container $containerName")
        } catch (_: NotFoundException) {
            // Container already gone — removal is idempotent.
            log.info("Container {} does not exist — treating remove as already-removed", containerName)
        }
        gate.markRemoved(serverIdOf(containerName))
    }

    override fun getContainerNetworkNames(containerName: String): List<String> = runCatching {
        docker.inspectContainerCmd(containerName)
            .exec()
            .networkSettings?.networks?.keys?.toList() ?: emptyList()
    }.getOrDefault(emptyList())

    override fun getContainerId(containerName: String): String? = runCatching {
        docker.inspectContainerCmd(containerName)
            .exec().id
    }.getOrNull()

    override fun execRconCommand(serverId: String, command: String) {
        val containerName = "$containerNamePrefix-$serverId"
        runCatching {
            val exec = docker.execCreateCmd(containerName)
                .withCmd("rcon-cli", command)
                .withAttachStdout(false)
                .withAttachStderr(false)
                .exec()
            docker.execStartCmd(exec.id)
                .withDetach(true)
                .exec(ResultCallback.Adapter())
        }.onFailure { log.warn("RCON exec failed for server $serverId: command='$command'", it) }
    }

    override fun isSwarmActive(): Boolean = runCatching {
        docker.infoCmd()
            .exec().swarm?.localNodeState?.name?.lowercase() == "active"
    }.getOrDefault(false)

    override fun attachInteractive(containerName: String, inputStream: InputStream, callback: ResultCallback<Frame>): ResultCallback<Frame> = docker.attachContainerCmd(containerName)
        .withStdIn(inputStream)
        .withStdOut(true)
        .withStdErr(true)
        .withFollowStream(true)
        .withLogs(false)
        .exec(callback)

    override fun fetchLogs(containerName: String, tailLines: Int, callback: ResultCallback<Frame>): ResultCallback<Frame> = docker.logContainerCmd(containerName)
        .withTail(tailLines)
        .withStdOut(true)
        .withStdErr(true)
        .withFollowStream(false)
        .exec(callback)

    override fun shutdownAll(timeoutSeconds: Int): Pair<Int, Int> {
        val containers = docker.listContainersCmd()
            .withShowAll(false)
            .exec()
            .filter { it.labels.containsKey("craftpanel.managed") }

        // Mark all as stopping first — prevents watcher from reporting unexpected deaths
        for (container in containers) {
            val serverId = container.labels["craftpanel.server.id"]
            if (serverId != null) gate.markStopping(serverId)
        }

        var graceful = 0
        var forced = 0

        for (container in containers) {
            val name = container.names.firstOrNull()
                ?.trimStart('/') ?: container.id
            val stopCommand = container.labels["craftpanel.stop.command"] ?: ""

            runCatching {
                stopContainer(name, timeoutSeconds, stopCommand)
                graceful++
            }.onFailure {
                log.warn("Graceful stop failed for $name — force stopping", it)
                runCatching {
                    docker.killContainerCmd(container.id)
                        .exec()
                }
                forced++
            }
        }

        return Pair(graceful, forced)
    }
}
