package io.craftpanel.agent.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.api.model.*
import org.slf4j.LoggerFactory

class McRouterProvisioner(
    private val docker: DockerClient,
    private val image: String,
    private val updateOnStart: Boolean,
    private val networkName: String = "",
    private val containerNamePrefix: String = "craftpanel",
) {

    private val log = LoggerFactory.getLogger(McRouterProvisioner::class.java)
    private val containerName = "$containerNamePrefix-mc-router"

    fun ensureRunning() {
        if (updateOnStart) {
            log.info("Pulling mc-router image $image")
            docker.pullImageCmd(image)
                .exec(PullImageResultCallback())
                .awaitCompletion()
        }

        val existing = docker.listContainersCmd()
            .withShowAll(true)
            .withNameFilter(listOf(containerName))
            .exec()
            .firstOrNull()

        if (existing != null) {
            if (existing.state == "running") {
                log.info("mc-router already running")
                connectToNetwork(existing.id)
                return
            }
            log.info("mc-router container exists but not running — starting")
            docker.startContainerCmd(existing.id)
                .exec()
            connectToNetwork(existing.id)
            return
        }

        log.info("Provisioning mc-router container ($image)")
        if (!updateOnStart) pullIfAbsent()
        val port = ExposedPort.tcp(25565)
        val bindings = Ports().apply { bind(port, Ports.Binding.bindPort(25565)) }
        val hostConfig = HostConfig.newHostConfig()
            .withPortBindings(bindings)
            .withBinds(Bind("/var/run/docker.sock", Volume("/var/run/docker.sock"), AccessMode.rw))
            .withRestartPolicy(RestartPolicy.unlessStoppedRestart())
        val id = docker.createContainerCmd(image)
            .withName(containerName)
            .withHostConfig(hostConfig)
            .exec().id
        docker.startContainerCmd(id)
            .exec()
        connectToNetwork(id)
        log.info("mc-router provisioned and started")
    }

    private fun connectToNetwork(containerId: String) {
        if (networkName.isEmpty()) return
        runCatching {
            docker.connectToNetworkCmd()
                .withNetworkId(networkName)
                .withContainerId(containerId)
                .exec()
        }.onFailure { log.warn("Could not connect mc-router to $networkName: ${it.message}") }
    }

    private fun pullIfAbsent() {
        val present = runCatching {
            docker.inspectImageCmd(image)
                .exec()
            true
        }.getOrDefault(false)
        if (!present) {
            log.info("Pulling mc-router image $image")
            docker.pullImageCmd(image)
                .exec(PullImageResultCallback())
                .awaitCompletion()
        }
    }
}
