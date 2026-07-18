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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

open class ContainerManager(
    private val docker: DockerClient,
    private val craftpanelNetwork: String = "",
    private val containerNamePrefix: String = "craftpanel",
    private val pullMaxImageAgeHours: Long = 24
) {

    private val log = LoggerFactory.getLogger(ContainerManager::class.java)

    // ponytail: two concurrent sets gate watcher; managed=owned-by-this-agent, stopping=intentionally-dying
    private val managedServerIds = ConcurrentHashMap.newKeySet<String>()
    private val stoppingServerIds = ConcurrentHashMap.newKeySet<String>()

    /** Returns true iff the agent owns this server and the death was NOT intentional. */
    fun shouldReportDie(serverId: String): Boolean = managedServerIds.contains(serverId) && !stoppingServerIds.contains(serverId)

    /** Call before stop/remove. Death is expected; watcher will suppress. */
    fun markStopping(serverId: String) = stoppingServerIds.add(serverId)

    /**
     * Call on successful start/restart. Clears the stopping flag (any lingering die event from
     * the prior removal has already been delivered by the time we reach here). Also marks managed
     * so future unexpected deaths are reported.
     */
    fun markStarted(serverId: String) {
        managedServerIds.add(serverId)
        stoppingServerIds.remove(serverId)
    }

    /** Call on successful remove (server is gone, no future deaths expected). */
    fun markRemoved(serverId: String) {
        managedServerIds.remove(serverId)
        stoppingServerIds.remove(serverId)
    }

    fun listRunningContainerIds(): List<Pair<String, String>> {
        return docker.listContainersCmd()
            .withShowAll(false)
            .exec()
            .filter { it.labels.containsKey("craftpanel.server.id") }
            .mapNotNull { container ->
                val serverId = container.labels["craftpanel.server.id"]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                serverId to container.id
            }
    }

    fun listContainers(): List<ContainerState> = docker.listContainersCmd()
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

    fun createContainer(cmd: StartContainerCommand): String {
        val minecraftPort = ExposedPort.tcp(cmd.internalListenPort)
        val portBindings = Ports()
        if (cmd.hostPort > 0) {
            portBindings.bind(minecraftPort, Ports.Binding.bindPort(cmd.hostPort))
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
            .withExposedPorts(minecraftPort)
            .withHostConfig(hostConfig)
            .withLabels(
                buildMap {
                    put("craftpanel.managed", "true")
                    put("craftpanel.server.id", cmd.serverId)
                    if (cmd.publicHostname.isNotEmpty()) {
                        // mc-router auto-discovery labels (https://github.com/itzg/mc-router).
                        // `mc-router.host` is the routing hostname; `mc-router.port` is the
                        // container-internal Minecraft port; `mc-router.network` tells mc-router
                        // which Docker network to dial the backend on (the shared craftpanel
                        // network both mc-router and this container are attached to).
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

    fun containerExists(containerName: String): Boolean = runCatching {
        docker.inspectContainerCmd(containerName)
            .exec()
        true
    }.getOrDefault(false)

    fun pullImage(image: String, maxAgeHours: Long = pullMaxImageAgeHours) {
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

    fun startContainer(containerName: String) {
        docker.startContainerCmd(containerName)
            .exec()
        log.info("Started container $containerName")
    }

    fun stopContainer(containerName: String, timeoutSeconds: Int, stopCommand: String) {
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

    fun removeContainer(containerName: String, force: Boolean) {
        try {
            docker.removeContainerCmd(containerName)
                .withForce(force)
                .exec()
            log.info("Removed container $containerName")
        } catch (_: NotFoundException) {
            // Container already gone — removal is idempotent.
            log.info("Container {} does not exist — treating remove as already-removed", containerName)
        }
    }

    fun getContainerNetworkNames(containerName: String): List<String> = runCatching {
        docker.inspectContainerCmd(containerName)
            .exec()
            .networkSettings?.networks?.keys?.toList() ?: emptyList()
    }.getOrDefault(emptyList())

    fun getContainerId(containerName: String): String? = runCatching {
        docker.inspectContainerCmd(containerName)
            .exec().id
    }.getOrNull()

    fun getContainerDataPath(containerName: String): String? = runCatching {
        docker.inspectContainerCmd(containerName)
            .exec()
            .hostConfig?.binds
            ?.firstOrNull { it.volume.path == "/data" }
            ?.path
    }.getOrNull()

    fun execRconCommand(serverId: String, command: String) {
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

    fun isSwarmActive(): Boolean = runCatching {
        docker.infoCmd()
            .exec().swarm?.localNodeState?.name?.lowercase() == "active"
    }.getOrDefault(false)

    fun attachInteractive(containerName: String, inputStream: InputStream, callback: ResultCallback<Frame>): ResultCallback<Frame> = docker.attachContainerCmd(containerName)
        .withStdIn(inputStream)
        .withStdOut(true)
        .withStdErr(true)
        .withFollowStream(true)
        .withLogs(false)
        .exec(callback)

    fun fetchLogs(containerName: String, tailLines: Int, callback: ResultCallback<Frame>): ResultCallback<Frame> = docker.logContainerCmd(containerName)
        .withTail(tailLines)
        .withStdOut(true)
        .withStdErr(true)
        .withFollowStream(false)
        .exec(callback)

    fun shutdownAll(timeoutSeconds: Int): Pair<Int, Int> {
        val containers = docker.listContainersCmd()
            .withShowAll(false)
            .exec()
            .filter { it.labels.containsKey("craftpanel.managed") }

        // Mark all as stopping first — prevents watcher from reporting unexpected deaths
        for (container in containers) {
            val serverId = container.labels["craftpanel.server.id"]
            if (serverId != null) stoppingServerIds.add(serverId)
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
