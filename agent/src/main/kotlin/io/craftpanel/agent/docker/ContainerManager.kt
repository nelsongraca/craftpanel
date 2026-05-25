package io.craftpanel.agent.docker

import com.craftpanel.agent.v1.ContainerState
import com.craftpanel.agent.v1.CreateContainerCommand
import com.craftpanel.agent.v1.containerState
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.api.model.AccessMode
import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.HostConfig
import com.github.dockerjava.api.model.Ports
import com.github.dockerjava.api.model.RestartPolicy
import com.github.dockerjava.api.model.Volume
import org.slf4j.LoggerFactory

class ContainerManager(private val docker: DockerClient) {
    private val log = LoggerFactory.getLogger(ContainerManager::class.java)

    fun listContainers(): List<ContainerState> {
        return docker.listContainersCmd()
            .withShowAll(true)
            .exec()
            .filter { it.names.any { n -> n.contains("craftpanel-") } }
            .map { container ->
                containerState {
                    containerId = container.id
                    containerName = container.names.firstOrNull()?.trimStart('/') ?: container.id
                    serverId = container.labels["craftpanel.server.id"] ?: ""
                    runState = when {
                        container.state == "running" -> ContainerState.RunState.RUNNING
                        container.state == "exited" && container.status.contains("(0)") -> ContainerState.RunState.STOPPED
                        else -> ContainerState.RunState.EXITED
                    }
                }
            }
    }

    fun createContainer(cmd: CreateContainerCommand): String {
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
            .withRestartPolicy(RestartPolicy.parse(cmd.restartPolicy.ifEmpty { "unless-stopped" }))
            .let { cfg ->
                if (cmd.ramMb > 0) cfg.withMemory(cmd.ramMb.toLong() * 1024 * 1024) else cfg
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
            .withLabels(mapOf("craftpanel.server.id" to cmd.serverId))
            .exec()

        log.info("Created container ${cmd.containerName} (server ${cmd.serverId})")
        return response.id
    }

    fun pullImage(image: String) {
        docker.pullImageCmd(image).exec(PullImageResultCallback()).awaitCompletion()
        log.info("Pulled image $image")
    }

    fun startContainer(containerName: String) {
        docker.startContainerCmd(containerName).exec()
        log.info("Started container $containerName")
    }

    fun stopContainer(containerName: String, timeoutSeconds: Int, stopCommand: String) {
        if (stopCommand.isNotEmpty()) {
            // TODO: attach to container stdin and send graceful stop command
            log.debug("Graceful stop command '{}' for {} (stdin attach not yet implemented)", stopCommand, containerName)
        }
        docker.stopContainerCmd(containerName)
            .withTimeout(timeoutSeconds.takeIf { it > 0 } ?: 30)
            .exec()
        log.info("Stopped container $containerName")
    }

    fun removeContainer(containerName: String, force: Boolean) {
        docker.removeContainerCmd(containerName)
            .withForce(force)
            .exec()
        log.info("Removed container $containerName")
    }

    fun shutdownAll(timeoutSeconds: Int): Pair<Int, Int> {
        val containers = docker.listContainersCmd()
            .withShowAll(false)
            .exec()
            .filter { it.labels.containsKey("craftpanel.server.id") }

        var graceful = 0
        var forced = 0

        for (container in containers) {
            val name = container.names.firstOrNull()?.trimStart('/') ?: container.id
            runCatching {
                docker.stopContainerCmd(container.id)
                    .withTimeout(timeoutSeconds.takeIf { it > 0 } ?: 30)
                    .exec()
                graceful++
            }.onFailure {
                log.warn("Graceful stop failed for $name — force stopping", it)
                runCatching { docker.killContainerCmd(container.id).exec() }
                forced++
            }
        }

        return Pair(graceful, forced)
    }
}
