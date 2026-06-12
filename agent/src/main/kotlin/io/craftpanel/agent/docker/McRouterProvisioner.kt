package io.craftpanel.agent.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.api.exception.ConflictException
import com.github.dockerjava.api.exception.NotModifiedException
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

        val existing = runCatching {
            docker.inspectContainerCmd(containerName)
                .exec()
        }.getOrNull()

        if (existing != null) {
            if (existing.state?.running == true) {
                log.info("mc-router already running")
                connectToNetwork(existing.id)
                return
            }
            log.info("mc-router container exists but not running — starting")
            runCatching {
                docker.startContainerCmd(existing.id)
                    .exec()
            }
                .onFailure { if (it !is NotModifiedException) throw it }
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
        val id = try {
            docker.createContainerCmd(image)
                .withName(containerName)
                .withHostConfig(hostConfig)
                .exec().id
        }
        catch (e: ConflictException) {
            // Race: another agent created the container between our list-check and create.
            // Use inspectContainerCmd (exact name lookup) — listContainersCmd name-filter
            // can miss a container that was just created due to Docker's eventual-consistent list.
            val inspected = runCatching {
                docker.inspectContainerCmd(containerName)
                    .exec()
            }.getOrNull()
                ?: throw e
            if (inspected.state?.running == true) {
                log.info("mc-router already running (lost create race)")
                connectToNetwork(inspected.id)
                return
            }
            inspected.id
        }
        runCatching {
            docker.startContainerCmd(id)
                .exec()
        }
            .onFailure { if (it !is NotModifiedException) throw it }
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
