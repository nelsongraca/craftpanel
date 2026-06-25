package io.craftpanel.agent.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.InspectContainerResponse
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
    private val nodeId: String = "",
) {

    private val log = LoggerFactory.getLogger(McRouterProvisioner::class.java)
    val containerName: String = if (nodeId.isNotEmpty())
        "$containerNamePrefix-mc-router-$nodeId"
    else
        "$containerNamePrefix-mc-router"

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
            // A router provisioned by an older agent may lack IN_DOCKER=true (no auto-discovery
            // → it ignores `mc-router.host` labels and routes nothing) or lack the published
            // 25565 host binding (→ unreachable for external ingress). Reusing such a container
            // as-is silently breaks ingress after an upgrade. Detect the drift and recreate.
            val hasAutoDiscovery = existing.config?.env
                ?.any { it == "IN_DOCKER=true" } == true
            val hasHostPort = existing.networkSettings?.ports?.bindings
                ?.keys?.any { it.port == 25565 } == true
            if (!hasAutoDiscovery || !hasHostPort) {
                log.info("mc-router drift (autoDiscovery=$hasAutoDiscovery hostPort=$hasHostPort) — recreating")
                runCatching {
                    docker.removeContainerCmd(existing.id)
                        .withForce(true)
                        .exec()
                }
                    .onFailure { log.warn("Failed to remove stale mc-router — continuing to recreate: ${it.message}") }
            }
            else if (existing.state?.running == true) {
                log.info("mc-router already running")
                connectToNetwork(existing.id)
                return
            }
            else {
                log.info("mc-router container exists but not running — starting")
                runCatching {
                    docker.startContainerCmd(existing.id)
                        .exec()
                }
                    .onFailure { if (it !is NotModifiedException) throw it }
                connectToNetwork(existing.id)
                return
            }
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
                // The port must be exposed for the host binding to take effect — without it
                // Docker silently drops the binding and 25565 is never published to the host,
                // leaving mc-router unreachable for external ingress.
                .withExposedPorts(port)
                // IN_DOCKER=true subscribes mc-router to the Docker event stream so it reads
                // per-container `mc-router.host`/`mc-router.port`/`mc-router.network` labels.
                // Without it the mounted docker socket is never used and labels are ignored.
                .withEnv("IN_DOCKER=true")
                .withHostConfig(hostConfig)
                .withLabels(mapOf("craftpanel.managed" to "true"))
                .exec().id
        }
        catch (e: ConflictException) {
            // Race: another colocated agent created the container between our inspect-check
            // and create. The name conflict is itself proof the container exists, so we
            // reuse it rather than rethrow. The winner may still be mid-provisioning, so
            // poll inspect generously — its create can lag several seconds behind the
            // conflict response on a busy daemon.
            var inspected: InspectContainerResponse? = null
            var attempts = 0
            while (inspected == null && attempts < 75) {
                inspected = runCatching {
                    docker.inspectContainerCmd(containerName)
                        .exec()
                }.getOrNull()
                if (inspected == null) Thread.sleep(200)
                attempts++
            }
            val container = inspected ?: throw e
            if (container.state?.running == true) {
                log.info("mc-router already running (lost create race)")
                connectToNetwork(container.id)
                return
            }
            // Exists but not yet started — start it (idempotent) and reuse.
            runCatching {
                docker.startContainerCmd(container.id)
                    .exec()
            }
                .onFailure { if (it !is NotModifiedException) throw it }
            log.info("mc-router reused after losing create race")
            connectToNetwork(container.id)
            return
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
