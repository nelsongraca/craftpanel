package io.craftpanel.agent.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.InspectContainerResponse
import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.api.exception.ConflictException
import com.github.dockerjava.api.exception.NotModifiedException
import com.github.dockerjava.api.model.*
import io.craftpanel.common.DockerLabels
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

class McRouterProvisioner(
    private val docker: DockerClient,
    private val imageProvider: () -> String,
    private val updateOnStart: Boolean,
    private val networkName: String = "",
    containerNameOverride: String = "",
    private val logLevel: String = DEFAULT_LOG_LEVEL,
    // Seam for the docker.sock group lookup; tests inject a fixed value so drift detection is
    // deterministic regardless of whether the host actually has docker.sock.
    private val socketGidProvider: () -> String? = ::readDockerSocketGid
) {

    companion object {
        const val DEFAULT_LOG_LEVEL = "warn"
    }

    private val log = LoggerFactory.getLogger(McRouterProvisioner::class.java)

    // Resolved on every use so a live change to the DB-backed image setting takes effect on the
    // next provision/drift check without restarting the agent.
    private val image: String get() = imageProvider()

    // One per host, shared by all co-located agents. Override via MCROUTER_CONTAINER_NAME.
    val containerName: String = containerNameOverride.ifBlank { "craftpanel-mc-router" }

    private val _updateAvailable = AtomicBoolean(false)

    /**
     * True when the running router's image id differs from the id the local tag now resolves to —
     * i.e. a newer image has been pre-pulled and is ready to apply at a planned downtime. Only
     * meaningful while the router is healthy; reset to false after a (re)create.
     */
    val updateAvailable: Boolean get() = _updateAvailable.get()

    /**
     * Explicit operator action: force-remove the router and provision it fresh, applying any
     * pre-pulled newer image. Never called implicitly while the router is healthy.
     */
    fun recreateNow(): Boolean {
        runCatching {
            docker.removeContainerCmd(containerName)
                .withForce(true)
                .exec()
        }.onFailure { log.warn("Failed to remove mc-router for explicit recreate: ${it.message}") }
        log.info("Explicit mc-router recreate requested")
        return ensureRunning()
    }

    /**
     * Ensures the router container exists and is running. Returns true when the router was created,
     * recreated, or reused from another agent (the caller must then reconcile its network
     * attachments); false when an existing, healthy router was left as-is.
     */
    fun ensureRunning(): Boolean {
        val existing = runCatching {
            docker.inspectContainerCmd(containerName)
                .exec()
        }.getOrNull()

        if (existing != null) {
            // Recreate if IN_DOCKER=true is missing — that's the flag that enables label-based
            // auto-discovery. Without it mc-router ignores container labels and routes nothing.
            // Also recreate if DYNAMIC_PROXY_PROTOCOL=true is missing — the fork flag that makes
            // mc-router emit PROXY protocol to backends that advertise support for it.
            // Also recreate if the configured LOG_LEVEL is missing — the container was created
            // before the log-level setting existed, or the configured level changed.
            // Also recreate if the docker.sock GID isn't in group_add — a container created by
            // an older agent build predates the group_add fix and will crash-loop forever on
            // permission-denied, and restarting it (the "exists but not running" branch below)
            // can never fix that since the group membership is fixed at container-creation time.
            // Also recreate when the configured image *reference* changed (an admin moved
            // MCROUTER_IMAGE / image_mc_router to a different tag) — that is a deliberate config
            // change, so it is applied eagerly.
            //
            // An *image update under the same tag* is deliberately NOT a recreate trigger while the
            // router is healthy: a background pre-pull moving the tag must never restart the shared
            // router out from under live connections. It is reported via [updateAvailable] instead,
            // and applied through the explicit [recreateNow] action at a planned downtime.
            //
            // The host-port binding is always set on creation and not re-checked here: Docker inspect
            // may not expose bindings via networkSettings.ports inside the container network, and
            // a running shared container must not be destroyed while co-located agents depend on it.
            val socketGid = this.socketGid
            val drift = routerContainerDrift(
                env = existing.config?.env.orEmpty().toList(),
                groupAdd = existing.hostConfig?.groupAdd,
                containerImage = existing.config?.image,
                expectedImage = image,
                socketGid = socketGid,
                expectedLogLevel = logLevel
            )
            if (drift.isNotEmpty()) {
                log.info("mc-router drift (${drift.joinToString(", ")}) — recreating")
                runCatching {
                    docker.removeContainerCmd(existing.id)
                        .withForce(true)
                        .exec()
                }
                    .onFailure { log.warn("Failed to remove stale mc-router — continuing to recreate: ${it.message}") }
            } else if (existing.state?.running == true) {
                _updateAvailable.set(routerImageUpdateAvailable(existing.imageId, localImageId()))
                log.debug("mc-router already running (updateAvailable={})", updateAvailable)
                connectToNetwork(existing.id)
                return false
            } else {
                // Down: recreate (not a bare start) so a pre-pulled newer image is applied.
                log.info("mc-router container exists but not running — recreating")
                runCatching {
                    docker.removeContainerCmd(existing.id)
                        .withForce(true)
                        .exec()
                }
                    .onFailure { log.warn("Failed to remove stopped mc-router — continuing to recreate: ${it.message}") }
            }
        }

        _updateAvailable.set(false)
        log.info("Provisioning mc-router container ($image)")
        if (updateOnStart) {
            log.info("Pulling mc-router image $image")
            docker.pullImageCmd(image)
                .exec(PullImageResultCallback())
                .awaitCompletion()
        } else {
            pullIfAbsent()
        }
        val port = ExposedPort.tcp(25565)
        val bindings = Ports().apply { bind(port, Ports.Binding.bindPort(25565)) }
        val hostConfig = HostConfig.newHostConfig()
            .withPortBindings(bindings)
            .withBinds(Bind("/var/run/docker.sock", Volume("/var/run/docker.sock"), AccessMode.rw))
            .withRestartPolicy(RestartPolicy.unlessStoppedRestart())
        // mc-router runs as a fixed nonroot UID with no entrypoint logic to join the socket's
        // group (unlike our own agent image). Without group_add it gets a permanent
        // permission-denied on docker.sock and crash-loops forever.
        socketGid?.let { hostConfig.withGroupAdd(listOf(it)) }
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
                // DYNAMIC_PROXY_PROTOCOL=true lets the fork send PROXY protocol to backends that
                // advertise support for it, preserving the real client IP at the backend.
                // LOG_LEVEL drops mc-router's default `info` verbosity to `warn` (configurable via
                // MCROUTER_LOG_LEVEL) so per-connection routing chatter is not emitted.
                .withEnv("IN_DOCKER=true", "DYNAMIC_PROXY_PROTOCOL=true", "LOG_LEVEL=$logLevel")
                .withHostConfig(hostConfig)
                .withLabels(mapOf(DockerLabels.MANAGED to DockerLabels.MANAGED_VALUE))
                .exec().id
        } catch (e: ConflictException) {
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
                return true
            }
            // Exists but not yet started — start it (idempotent) and reuse.
            runCatching {
                docker.startContainerCmd(container.id)
                    .exec()
            }
                .onFailure { if (it !is NotModifiedException) throw it }
            log.info("mc-router reused after losing create race")
            connectToNetwork(container.id)
            return true
        }
        runCatching {
            docker.startContainerCmd(id)
                .exec()
        }.onFailure { e ->
            if (e is NotModifiedException) {
                connectToNetwork(id)
                log.info("mc-router already started (NotModified on start)")
                return true
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
                    return true
                }
            }
            throw e
        }
        connectToNetwork(id)
        log.info("mc-router provisioned and started")
        return true
    }

    private fun findExistingRouterOnPort(port: Int): InspectContainerResponse? = runCatching {
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
        docker.connectIfAbsent(networkName, containerId)
    }

    /** Image id the configured tag currently resolves to, or null when absent/uninspectable. */
    private fun localImageId(): String? = runCatching {
        docker.inspectImageCmd(image)
            .exec().id
    }.getOrNull()

    // Cached only on success: doesn't change while the agent process is alive, and
    // ensureRunning() is polled periodically by RouterSupervisor — avoids forking `stat` on
    // every tick. A failed lookup is NOT cached (retried each call) — a transient failure on
    // an early tick (e.g. socket not yet mounted) must not permanently disable the drift check
    // for the rest of the agent's lifetime.
    private var cachedSocketGid: String? = null
    private val socketGid: String?
        get() = cachedSocketGid ?: socketGidProvider()?.also { cachedSocketGid = it }

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

/**
 * Names of the configuration checks an existing mc-router container fails; empty when it matches the
 * agent's configured router. Covers the label-based auto-discovery env flags, the configured log
 * level, the docker.sock group membership, and the container image. Pure so the drift matrix is
 * table-testable — a non-empty result means the container must be recreated.
 */
internal fun routerContainerDrift(
    env: List<String>,
    groupAdd: List<String>?,
    containerImage: String?,
    expectedImage: String,
    socketGid: String?,
    expectedLogLevel: String = McRouterProvisioner.DEFAULT_LOG_LEVEL
): List<String> = buildList {
    if (env.none { it == "IN_DOCKER=true" }) add("autoDiscovery")
    if (env.none { it == "DYNAMIC_PROXY_PROTOCOL=true" }) add("dynamicProxyProtocol")
    if (env.none { it == "LOG_LEVEL=$expectedLogLevel" }) add("logLevel")
    if (socketGid != null && groupAdd?.contains(socketGid) != true) add("socketGroup")
    if (containerImage != expectedImage) add("image")
}

/**
 * True when a same-tag newer image is ready: the router container was created from a different
 * image id than the tag now resolves to. Only meaningful when both ids are known — an absent or
 * uninspectable tag must never be reported as an update.
 */
internal fun routerImageUpdateAvailable(containerImageId: String?, expectedImageId: String?): Boolean =
    !containerImageId.isNullOrEmpty() && !expectedImageId.isNullOrEmpty() && containerImageId != expectedImageId

/** Reads the docker.sock group id so mc-router can be granted access to the mounted socket. */
private fun readDockerSocketGid(): String? = runCatching {
    ProcessBuilder("stat", "-c", "%g", "/var/run/docker.sock")
        .redirectErrorStream(true)
        .start()
        .let { it.inputStream.bufferedReader().readText().trim().toLong().also { _ -> it.waitFor() } }
}.onFailure {
    LoggerFactory.getLogger(McRouterProvisioner::class.java)
        .warn("Could not stat docker.sock GID — mc-router may fail with permission denied: ${it.message}")
}.getOrNull()?.toString()
