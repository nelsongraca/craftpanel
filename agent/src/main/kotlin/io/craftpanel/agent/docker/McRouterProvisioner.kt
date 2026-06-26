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
    containerNameOverride: String = "",
) {

    private val log = LoggerFactory.getLogger(McRouterProvisioner::class.java)

    // One per host, shared by all co-located agents. Override via MCROUTER_CONTAINER_NAME.
    val containerName: String = containerNameOverride.ifBlank { "craftpanel-mc-router" }

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
            // Recreate only if IN_DOCKER=true is missing — that's the flag that enables label-based
            // auto-discovery. Without it mc-router ignores container labels and routes nothing.
            // The host-port binding is always set on creation and not re-checked here: Docker inspect
            // may not expose bindings via networkSettings.ports inside the container network, and
            // a running shared container must not be destroyed while co-located agents depend on it.
            val hasAutoDiscovery = existing.config?.env
                ?.any { it == "IN_DOCKER=true" } == true
            if (!hasAutoDiscovery) {
                log.info("mc-router drift (autoDiscovery=false) — recreating")
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
        }.onFailure { e ->
            if (e is NotModifiedException) {
                connectToNetwork(id)
                log.info("mc-router already started (NotModified on start)")
                return
            }
            // Port 25565 may be bound by another mc-router on the same host (co-located agents).
            // Find it and reuse rather than failing — both agents share one host port.
            if (e.message?.contains("port is already allocated") == true ||
                e.message?.contains("address already in use") == true
            ) {
                val existing = findExistingRouterOnPort(25565)
                if (existing != null) {
                    log.info("mc-router port conflict — reusing existing router ${existing.id}")
                    connectToNetwork(existing.id)
                    return
                }
            }
            throw e
        }
        connectToNetwork(id)
        log.info("mc-router provisioned and started")
    }

    private fun findExistingRouterOnPort(port: Int): InspectContainerResponse? =
        runCatching {
            docker.listContainersCmd()
                .exec()
                .firstOrNull { c ->
                    c.ports?.any { p -> p.publicPort == port } == true &&
                            c.image?.contains("mc-router") == true
                }
                ?.let {
                    docker.inspectContainerCmd(it.id)
                        .exec()
                }
        }.getOrNull()

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
