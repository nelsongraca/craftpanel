package io.craftpanel.agent.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.api.exception.ConflictException
import com.github.dockerjava.api.exception.NotFoundException
import com.github.dockerjava.api.model.*
import io.craftpanel.common.ContainerNames
import io.craftpanel.common.DockerLabels
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
    private val containerNamePrefix: String = ContainerNames.DEFAULT_PREFIX,
    private val pullMaxImageAgeHours: Long = 24
) : ContainerManager {

    private val log = LoggerFactory.getLogger(DockerContainerManager::class.java)

    private val names = ContainerNames(containerNamePrefix)

    private fun serverIdOf(containerName: String): String = names.serverIdOf(containerName)

    override fun listRunningContainers(): List<RunningContainer> {
        return docker.listContainersCmd()
            .withShowAll(false)
            .exec()
            .filter { it.labels.containsKey("craftpanel.server.id") }
            .mapNotNull { container ->
                val serverId = container.labels["craftpanel.server.id"]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                RunningContainer(
                    serverId = serverId,
                    containerId = container.id
                )
            }
    }

    override fun listContainers(): List<ContainerState> = docker.listContainersCmd()
        .withShowAll(true)
        .exec()
        .filter { it.names.any { n -> names.isManagedContainerName(n.trimStart('/')) } }
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
                // Hard CPU cap: millicores → docker --cpus (nanocpus = millicores * 1e6).
                if (cmd.cpuLimitMillicores > 0) cfg.withNanoCPUs(cmd.cpuLimitMillicores.toLong() * 1_000_000L) else cfg
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
                    put(DockerLabels.MANAGED, DockerLabels.MANAGED_VALUE)
                    put("craftpanel.server.id", cmd.serverId)
                    put(DockerLabels.MANAGED_ENV_KEYS, cmd.envVarsMap.keys.sorted().joinToString(","))
                    if (cmd.publicHostname.isNotEmpty() && !isUdp) {
                        // mc-router auto-discovery labels (https://github.com/itzg/mc-router).
                        // `mc-router.host` is the routing hostname; `mc-router.port` is the
                        // container-internal Minecraft port; `mc-router.network` tells mc-router
                        // which Docker network to dial the backend on (the container's own server
                        // network, which mc-router is attached to). UDP backends cannot be proxied
                        // by mc-router, so skip the labels.
                        put("mc-router.host", cmd.publicHostname)
                        put("mc-router.port", cmd.internalListenPort.toString())
                        if (cmd.dockerNetwork.isNotEmpty()) {
                            put("mc-router.network", cmd.dockerNetwork)
                        }
                    }
                    if (cmd.stopCommand.isNotEmpty()) {
                        put("craftpanel.stop.command", cmd.stopCommand)
                    }
                }
            )
            .withStdinOpen(true)
            .let { createCmd ->
                // The server name is the container hostname, so it resolves as a DNS name on the
                // shared network (Docker folds Config.Hostname into the endpoint's DNS names).
                if (cmd.serverName.isNotEmpty()) createCmd.withHostName(cmd.serverName) else createCmd
            }
            .let { createCmd ->
                if (cmd.containerUser.isNotEmpty()) createCmd.withUser(cmd.containerUser) else createCmd
            }
            .exec()

        log.info("Created container ${cmd.containerName} (server ${cmd.serverId})")
        return response.id
    }

    override fun containerExists(containerName: String): Boolean = runCatching {
        docker.inspectContainerCmd(containerName)
            .exec()
        true
    }.getOrDefault(false)

    override fun isRunning(containerName: String): Boolean = runCatching {
        docker.inspectContainerCmd(containerName)
            .exec().state?.running ?: false
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
        val timeout = timeoutSeconds.takeIf { it > 0 } ?: ContainerManager.DEFAULT_STOP_TIMEOUT_SECONDS

        when (val action = parseStopAction(stopCommand)) {
            is StopAction.Signal -> {
                val exited = sendSignalToContainer(containerName, action.signal, timeout)
                if (exited) {
                    log.info("Container {} exited cleanly after signal {}", containerName, action.signal)
                    return
                }
                log.warn("Container {} did not exit within {}s after signal {} — force stopping", containerName, timeout, action.signal)
            }

            StopAction.WriteText -> {
                val exited = sendStopCommandToStdin(containerName, stopCommand, timeout)
                if (exited) {
                    log.info("Container {} exited cleanly after stop command", containerName)
                    return
                }
                log.warn("Container {} did not exit within {}s after stop command — force stopping", containerName, timeout)
            }

            // Empty stop command — proceed directly to Docker stop.
            StopAction.Skip -> {}
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

    /**
     * Sends a Unix signal to the container's main process (PID 1) — the same signal a
     * terminal Ctrl+C (SIGINT) would deliver. Best-effort: whether the image's entrypoint
     * forwards the signal to the server process is the same contract `docker stop`
     * (SIGTERM) already relies on.
     */
    private fun sendSignalToContainer(containerName: String, signal: String, timeoutSeconds: Int): Boolean = runCatching {
        docker.killContainerCmd(containerName)
            .withSignal(signal)
            .exec()
        waitForExit(containerName, timeoutSeconds)
    }.getOrElse { e ->
        log.warn("Failed to send signal {} to container {}: {}", signal, containerName, e.message)
        false
    }

    /** Polls container state until it exits (or inspect fails), up to [timeoutSeconds]. */
    private fun waitForExit(containerName: String, timeoutSeconds: Int): Boolean {
        val timeoutNanos = TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
        val deadline = System.nanoTime() + timeoutNanos
        while (System.nanoTime() < deadline) {
            val running = runCatching {
                docker.inspectContainerCmd(containerName)
                    .exec().state?.running ?: false
            }.getOrDefault(false)
            if (!running) return true
            Thread.sleep(500)
        }
        return false
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
        } catch (_: ConflictException) {
            // Container exists but is not running — desired state already achieved.
            log.info("Container {} is not running — treating force-kill as already-stopped", containerName)
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

    override fun inspectContainer(containerName: String): ContainerSnapshot? = runCatching {
        val info = docker.inspectContainerCmd(containerName)
            .exec()
        val config = info.config
        val hostConfig = info.hostConfig

        val env = config?.env.orEmpty()
            .mapNotNull { pair ->
                val i = pair.indexOf('=')
                if (i > 0) pair.substring(0, i) to pair.substring(i + 1) else null
            }
            .toMap()

        val binds = info.mounts.orEmpty()
            .mapNotNull { m ->
                val destination = m.destination?.path ?: return@mapNotNull null
                BindSnapshot(hostPath = m.source ?: "", containerPath = destination, readOnly = m.rw == false)
            }

        val portBindings = hostConfig?.portBindings?.bindings.orEmpty()
            .flatMap { (exposed, bindings) ->
                bindings.orEmpty()
                    .mapNotNull { binding ->
                        val hostPort = binding?.hostPortSpec?.takeIf { it.isNotBlank() }
                            ?.toIntOrNull()
                            ?: return@mapNotNull null
                        PortBindingSnapshot(exposed.port, exposed.protocol.name.lowercase(), hostPort)
                    }
            }

        ContainerSnapshot(
            image = config?.image ?: "",
            env = env,
            binds = binds,
            portBindings = portBindings,
            user = config?.user ?: "",
            memoryMb = ((hostConfig?.memory ?: 0L) / (1024 * 1024)).toInt(),
            cpuLimitMillicores = cpuLimitMillicoresOf(hostConfig),
            labels = config?.labels.orEmpty(),
            networkMode = hostConfig?.networkMode ?: "",
            hostname = config?.hostName ?: "",
            running = info.state?.running ?: false,
            networks = info.networkSettings?.networks?.keys.orEmpty()
        )
    }.getOrNull()

    /**
     * Effective hard CPU cap in millicores. Docker sets `NanoCpus` when the container was
     * created with `--cpus`; older/daemon-set containers instead carry `CpuQuota`/`CpuPeriod`,
     * so fall back to quota/period. 0 means no limit.
     */
    private fun cpuLimitMillicoresOf(hostConfig: com.github.dockerjava.api.model.HostConfig?): Int {
        val nanoCpus = hostConfig?.nanoCPUs ?: 0L
        if (nanoCpus > 0) return (nanoCpus / 1_000_000L).toInt()
        val quota = hostConfig?.cpuQuota ?: 0L
        val period = hostConfig?.cpuPeriod ?: 0L
        return if (quota > 0 && period > 0) (quota * 1000L / period).toInt() else 0
    }

    override fun execRconCommand(serverId: String, command: String) {
        val containerName = names.container(serverId)
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
            .filter { it.labels.containsKey(DockerLabels.MANAGED) }

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

/** How to interpret a configured stop command when stopping a container. */
private sealed interface StopAction {

    /** Empty stop command — skip any stdin/signal step, Docker stop directly. */
    data object Skip : StopAction

    /** The container's main process receives this Unix signal (e.g. SIGINT). */
    data class Signal(val signal: String) : StopAction

    /** The command is written verbatim to container stdin. */
    data object WriteText : StopAction
}

/**
 * Sentinel stop commands that deliver a Unix signal instead of text to stdin:
 *
 * | value     | signal    | meaning                                        |
 * |-----------|-----------|------------------------------------------------|
 * | `^C`      | SIGINT    | terminal Ctrl+C                                |
 * | `^\`      | SIGQUIT   | terminal Ctrl+\ (thread dump / core dump)      |
 * | `SIG*`    | as-named  | any explicit signal name (SIGTERM, SIGHUP, …)  |
 *
 * Anything else is treated as a text stop command; empty skips stdin entirely.
 */
private fun parseStopAction(command: String): StopAction = when {
    command.isEmpty() -> StopAction.Skip
    command == "^C" -> StopAction.Signal("SIGINT")
    command == "^\\" -> StopAction.Signal("SIGQUIT")
    command.matches(SIGNAL_NAME_REGEX) -> StopAction.Signal(command)
    else -> StopAction.WriteText
}

private val SIGNAL_NAME_REGEX = Regex("SIG[A-Z0-9]+")
