package io.craftpanel.agent.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.api.exception.NotFoundException
import com.github.dockerjava.api.model.*
import io.craftpanel.proto.ContainerState
import io.craftpanel.proto.StartContainerCommand
import io.craftpanel.proto.containerState
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

open class ContainerManager(
    private val docker: DockerClient,
    private val craftpanelNetwork: String = "",
    private val containerNamePrefix: String = "craftpanel",
) {

    private val log = LoggerFactory.getLogger(ContainerManager::class.java)

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

    fun listContainers(): List<ContainerState> {
        return docker.listContainersCmd()
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
                        "running"                                    -> ContainerState.RunState.RUNNING
                        "exited" if container.status.contains("(0)") -> ContainerState.RunState.STOPPED
                        else                                         -> ContainerState.RunState.EXITED
                    }
                }
            }
    }

    fun createContainer(cmd: StartContainerCommand): String {
        val minecraftPort = ExposedPort.tcp(25565)
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
            .withRestartPolicy(RestartPolicy.parse("unless-stopped"))
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
            .withLabels(buildMap {
                put("craftpanel.server.id", cmd.serverId)
                if (cmd.publicHostname.isNotEmpty()) {
                    put("mc-router.hostname", cmd.publicHostname)
                }
            })
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

    fun containerExists(containerName: String): Boolean =
        runCatching {
            docker.inspectContainerCmd(containerName)
                .exec()
            true
        }.getOrDefault(false)

    fun pullImage(image: String) {
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
        }
        catch (_: NotFoundException) {
            // Container already gone (e.g. server was never started) — stopping is
            // idempotent, the desired end state (not running) already holds.
            log.info("Container {} does not exist — treating stop as already-stopped", containerName)
        }
    }

    private fun sendStopCommandToStdin(containerName: String, command: String, timeoutSeconds: Int): Boolean =
        runCatching {
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
        }
        catch (_: NotFoundException) {
            // Container already gone — removal is idempotent.
            log.info("Container {} does not exist — treating remove as already-removed", containerName)
        }
    }

    fun getContainerDataPath(containerName: String): String? {
        return runCatching {
            docker.inspectContainerCmd(containerName)
                .exec()
                .hostConfig?.binds
                ?.firstOrNull { it.volume.path == "/data" }
                ?.path
        }.getOrNull()
    }

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

    fun startRsyncdContainer(migrationId: String, port: Int, destPath: String, password: String, rsyncImage: String): String {
        File(destPath).mkdirs()
        val containerName = "$containerNamePrefix-rsync-recv-$migrationId"
        val portBinding = ExposedPort.tcp(port)
        val portBindings = Ports()
        portBindings.bind(portBinding, Ports.Binding.bindPort(port))

        val script = """
            set -e
            apk add rsync --quiet --no-progress
            mkdir -p /etc/rsyncd
            cat > /etc/rsyncd.conf << 'CONF'
[data]
path = /data
read only = no
auth users = craftpanel
secrets file = /etc/rsyncd/secrets
CONF
            echo "craftpanel:${password}" > /etc/rsyncd/secrets  # password is alphanumeric-only — safe for unquoted rsyncd secrets file
            chmod 600 /etc/rsyncd/secrets
            rsync --daemon --no-detach --port $port --config /etc/rsyncd.conf
        """.trimIndent()

        val hostConfig = HostConfig.newHostConfig()
            .withPortBindings(portBindings)
            .withBinds(Bind(destPath, Volume("/data"), AccessMode.rw))
            .withRestartPolicy(RestartPolicy.noRestart())
            .let { hc -> if (craftpanelNetwork.isNotBlank()) hc.withNetworkMode(craftpanelNetwork) else hc }

        docker.createContainerCmd(rsyncImage)
            .withName(containerName)
            .withCmd("sh", "-c", script)
            .withExposedPorts(portBinding)
            .withHostConfig(hostConfig)
            .exec()
        docker.startContainerCmd(containerName)
            .exec()
        log.info("Started rsyncd container $containerName on port $port")
        return containerName
    }

    fun runRsyncTransfer(
        migrationId: String,
        sourcePath: String,
        destIp: String,
        destPort: Int,
        password: String,
        isFinalPass: Boolean,
        rsyncImage: String,
        onProgress: (bytesTransferred: Long, totalBytes: Long, percent: Int, phase: String) -> Unit,
    ): Boolean {
        val containerName = "$containerNamePrefix-rsync-send-${migrationId}${if (isFinalPass) "-final" else ""}"
        val script = """
            set -e
            apk add rsync --quiet --no-progress
            rsync -az --progress --stats /source/ rsync://craftpanel@${destIp}:${destPort}/data/
        """.trimIndent()

        val hostConfig = HostConfig.newHostConfig()
            .withBinds(Bind(sourcePath, Volume("/source"), AccessMode.ro))
            .withRestartPolicy(RestartPolicy.noRestart())
            .let { hc -> if (craftpanelNetwork.isNotBlank()) hc.withNetworkMode(craftpanelNetwork) else hc }

        docker.createContainerCmd(rsyncImage)
            .withName(containerName)
            .withCmd("sh", "-c", script)
            .withEnv("RSYNC_PASSWORD=$password")
            .withHostConfig(hostConfig)
            .exec()
        docker.startContainerCmd(containerName)
            .exec()

        val latch = CountDownLatch(1)
        val outputBuf = StringBuilder()
        docker.logContainerCmd(containerName)
            .withStdOut(true)
            .withStdErr(true)
            .withFollowStream(true)
            .exec(object : ResultCallback.Adapter<Frame>() {
                override fun onNext(frame: Frame) {
                    val line = String(frame.payload).trim()
                    outputBuf.appendLine(line)
                    parseRsyncProgress(line)?.let { p ->
                        onProgress(p.bytes, p.total, p.percent, p.phase)
                    }
                }

                override fun onComplete() = latch.countDown()
                override fun onError(t: Throwable) = latch.countDown()
            })

        latch.await(4, TimeUnit.HOURS)

        val exitCode = runCatching {
            docker.inspectContainerCmd(containerName)
                .exec().state?.exitCodeLong ?: 1
        }.getOrDefault(1L)

        runCatching {
            docker.removeContainerCmd(containerName)
                .withForce(true)
                .exec()
        }
        return exitCode == 0L
    }

    private data class RsyncProgress(val bytes: Long, val total: Long, val percent: Int, val phase: String)

    private fun parseRsyncProgress(line: String): RsyncProgress? {
        val progressRegex = Regex("""^\s*([\d,]+)\s+(\d+)%""")
        val match = progressRegex.find(line) ?: return null
        val bytes = match.groupValues[1].replace(",", "")
            .toLongOrNull() ?: return null
        val pct = match.groupValues[2].toIntOrNull() ?: return null
        return RsyncProgress(bytes, 0L, pct, "transferring")
    }

    fun shutdownAll(timeoutSeconds: Int): Pair<Int, Int> {
        val containers = docker.listContainersCmd()
            .withShowAll(false)
            .exec()
            .filter { it.labels.containsKey("craftpanel.server.id") }

        var graceful = 0
        var forced = 0

        for (container in containers) {
            val name = container.names.firstOrNull()
                ?.trimStart('/') ?: container.id
            runCatching {
                docker.stopContainerCmd(container.id)
                    .withTimeout(timeoutSeconds.takeIf { it > 0 } ?: 30)
                    .exec()
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
