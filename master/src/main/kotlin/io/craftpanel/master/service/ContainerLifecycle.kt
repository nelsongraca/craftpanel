package io.craftpanel.master.service

import io.craftpanel.common.ContainerNames
import io.craftpanel.master.config.ImagesConfig
import io.craftpanel.master.database.entity.Server
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.domain.DesiredStatus
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
import io.craftpanel.proto.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class ContainerLifecycle(
    private val gateway: AgentGateway,
    private val modService: ModService,
    private val serverRepository: ServerRepository,
    private val envVarsRepository: EnvVarsRepository,
    private val extraPortRepository: ServerExtraPortRepository = ServerExtraPortRepositoryImpl(),
    private val images: ImagesConfig = ImagesConfig("itzg/minecraft-server", "itzg/mc-proxy"),
    private val containerNamePrefix: String = "craftpanel",
    private val restartBudgetProvider: () -> Pair<Int, Long> = { 5 to 600L },
    private val stopTimeout: Duration = 45.seconds,
    private val startTimeout: Duration = 30.seconds,
    private val removeTimeout: Duration = 10.seconds
) {

    private val names = ContainerNames(containerNamePrefix)

    // ── Declarative desired-state (master intent setter) ──────────────────────

    /**
     * Sends a `ServerDesiredState` envelope carrying master's intent, the full runtime spec, and the
     * current restart budget. The agent stores it and converges (owning crash-restart within budget).
     * Returns false when the agent is not connected so the caller can map it to a 503.
     */
    fun sendDesiredState(
        server: ServerView,
        desired: DesiredStatus,
        nodeId: String = server.nodeId.toString(),
        force: Boolean = false,
        forceRestart: Boolean = false,
        publicHostname: String? = null,
        noRestart: Boolean = false
    ): Boolean = send(nodeId, buildDesiredStateMessage(server, desired, force, forceRestart, publicHostname, noRestart))

    fun sendRemove(server: ServerView, nodeId: String, force: Boolean = false): Boolean {
        val id = server.id
        return send(
            nodeId,
            masterMessage {
                removeContainer = removeContainerCommand {
                    serverId = id.toString()
                    containerName = names.container(id.toString())
                    this.force = force
                }
            }
        )
    }

    private fun setDesiredStatus(id: Uuid, value: String?) {
        transaction { Server.findById(id)?.let { it.desiredStatus = value } }
    }

    /**
     * Persists master's intent for a server. Used by reconciliation when a row has no recorded
     * intent and it is re-derived from the agent-reported status (see [DesiredStateSyncService]).
     * The public counterpart of [setDesiredStatus]; keeps the raw-null write private.
     */
    fun persistDesiredStatus(serverId: Uuid, desired: DesiredStatus) {
        setDesiredStatus(serverId, desired.toDb())
    }

    // ── Await-based primitives (used by MigrationService for cross-node relocation) ─

    /**
     * Sets desired RUNNING and waits for the agent to report HEALTHY. Reverts the desired status on
     * failure so a failed migration step does not strand the server in an unstartable intent.
     */
    suspend fun start(server: ServerView, publicHostname: String? = null, nodeId: String = server.nodeId.toString()) {
        ensureStartable(server)
        val id = server.id
        val previous = serverRepository.findById(id)?.desiredStatus
        setDesiredStatus(id, DesiredStatus.RUNNING.toDb())
        try {
            awaitStatus(id.toString(), ServerStatus.HEALTHY, startTimeout) {
                if (!sendDesiredState(server, DesiredStatus.RUNNING, nodeId, publicHostname = publicHostname)) {
                    throw BadGatewayException("Agent not connected")
                }
            }
        } catch (e: Exception) {
            setDesiredStatus(id, previous)
            throw e
        }
    }

    /** Sets desired STOPPED and waits for the agent to report STOPPED. Reverts the intent on failure. */
    suspend fun stop(server: ServerView, nodeId: String) {
        val id = server.id
        val previous = serverRepository.findById(id)?.desiredStatus
        setDesiredStatus(id, DesiredStatus.STOPPED.toDb())
        try {
            awaitStatus(id.toString(), ServerStatus.STOPPED, stopTimeout) {
                if (!sendDesiredState(server, DesiredStatus.STOPPED, nodeId)) {
                    throw BadGatewayException("Agent not connected")
                }
            }
        } catch (e: Exception) {
            setDesiredStatus(id, previous)
            throw e
        }
    }

    suspend fun remove(server: ServerView, nodeId: String, force: Boolean = false) {
        val id = server.id
        awaitStatus(id.toString(), ServerStatus.STOPPED, removeTimeout) {
            if (!sendRemove(server, nodeId, force)) throw BadGatewayException("Agent not connected")
        }
    }

    // ── Build helpers ─────────────────────────────────────────────────────────

    fun buildStartSpec(server: ServerView, publicHostname: String? = null): StartContainerCommand {
        val id = server.id
        val image = deriveImage(server.serverType, server.itzgImageTag)
        val allVars = buildAllVars(server)
        val resolvedHostname = publicHostname ?: server.dnsRecordName ?: "$id.mc.internal"
        val extraPortRows = extraPortRepository.findByServerId(id)
        val extraPortPb = extraPortRows.map { extra ->
            extraPortBinding {
                hostPort = extra.hostPort
                containerPort = extra.containerPort
                protocol = extra.protocol
                name = extra.name
            }
        }
        return startContainerCommand {
            serverId = id.toString()
            containerName = names.container(id.toString())
            stopCommand = server.stopCommand
            this.image = image
            envVars.putAll(allVars)
            this.publicHostname = resolvedHostname
            hostPort = server.hostPort
            memoryMb = server.memoryMb
            cpuLimitMillicores = server.cpuLimitMillicores
            dockerNetwork = server.networkId
                ?.let { names.sharedNetwork(it.toString()) }
                ?: names.standaloneNetwork(id.toString())
            dataContainerPath = images.dataContainerPath(server.serverType)
            internalListenPort = server.containerListenPort ?: images.internalListenPort(server.serverType)
            containerProtocol = server.containerProtocol
            serverName = server.name
            dataDirName = server.dataDirName ?: ""
            extraPorts.addAll(extraPortPb)
            containerUser = if (server.serverType.isPicolimbo) "1000:1000" else ""
        }
    }

    private fun buildDesiredStateMessage(server: ServerView, desired: DesiredStatus, force: Boolean, forceRestart: Boolean, publicHostname: String?, noRestart: Boolean): MasterMessage {
        val (maxAttempts, windowSeconds) = restartBudgetProvider()
        return masterMessage {
            serverDesiredState = serverDesiredState {
                serverId = server.id.toString()
                this.desired = when (desired) {
                    DesiredStatus.RUNNING -> ServerDesiredState.Desired.RUNNING
                    DesiredStatus.STOPPED -> ServerDesiredState.Desired.STOPPED
                }
                this.spec = buildStartSpec(server, publicHostname)
                this.force = force
                this.forceRestart = forceRestart
                this.noRestart = noRestart
                this.restartBudget = restartBudget {
                    this.maxAttempts = maxAttempts
                    this.windowSeconds = windowSeconds
                }
            }
        }
    }

    private fun buildAllVars(server: ServerView): Map<String, String> {
        val id = server.id
        val isPicolimbo = server.serverType.isPicolimbo
        val isManual = server.configMode == "MANUAL"
        var dbEnvVars = envVarsRepository.getEnvVars(id)
            .associate { it.key to it.value }
        if (isManual) {
            // Master owns server.properties in manual mode; itzg's JVM-flag env vars would
            // still apply on top of a hand-edited startup script, so strip them too.
            dbEnvVars = dbEnvVars - setOf("USE_AIKAR_FLAGS", "USE_MEOWICE_FLAGS", "JVM_OPTS", "JVM_XX_OPTS")
        }
        if (isPicolimbo) {
            // PicoLimbo is a native Rust binary — no itzg env vars apply.
            // Only user-defined env vars are passed through.
            return dbEnvVars
        }
        val modrinthProjects = modService.buildModrinthEnvVar(id)
        val isProxy = server.serverType.isProxy
        val isCustom = server.serverType.isCustom
        val systemVars = buildMap {
            put("EULA", "TRUE")
            put("TYPE", server.serverType.toDb())
            put("VERSION", if (isCustom) "LATEST" else server.mcVersion)
            // itzg/mc-proxy requires MINECRAFT_VERSION (not just VERSION) when MODRINTH_PROJECTS
            // is set, or its plugin-injection init aborts. Mirror VERSION for proxy types only.
            if (isProxy) put("MINECRAFT_VERSION", server.mcVersion)
            // Force the internal listen port so container port, mc-router label, and
            // healthcheck all agree across server types. Proxies (Velocity/BungeeCord/
            // Waterfall) listen on 25577; game servers on 25565. CUSTOM servers can declare
            // an override via container_listen_port. Overridable via per-server env var
            // (dbEnvVars wins on collision below).
            put(
                "SERVER_PORT",
                (server.containerListenPort ?: images.internalListenPort(server.serverType))
                    .toString()
            )
            put("MEMORY", "${defaultHeapMb(server.memoryMb)}M")
            if (modrinthProjects.isNotEmpty()) put("MODRINTH_PROJECTS", modrinthProjects)
            if (isProxy && !isManual) put("PATCH_DEFINITIONS", "/server/craftpanel-patch.json")
            if (isCustom) {
                val jar = server.customServerJar
                if (!jar.isNullOrBlank()) put("CUSTOM_SERVER", jar)
                if (server.disableHealthcheck) put("DISABLE_HEALTHCHECK", "true")
                if (server.forceRedownload) put("FORCE_REDOWNLOAD", "true")
            }
            // Manual mode is master-owned: never persisted to server_env_vars, always injected
            // fresh so itzg leaves server.properties alone.
            if (isManual) put("OVERRIDE_SERVER_PROPERTIES", "false")
        }
        return systemVars + dbEnvVars
    }

    // ── Core await primitive ──────────────────────────────────────────────────

    private suspend fun awaitStatus(serverId: String, expected: ServerStatus, timeout: Duration, sendCommand: () -> Unit): Unit = coroutineScope {
        val found = CompletableDeferred<Unit>()
        val job = launch {
            gateway.agentEvents
                .filterIsInstance<AgentEvent.ServerStatusEvent>()
                .collect { event ->
                    if (event.serverId == serverId) {
                        when {
                            event.status == expected ->
                                found.complete(Unit)

                            event.status == ServerStatus.UNHEALTHY ->
                                found.completeExceptionally(
                                    ContainerLifecycleException(
                                        "step failed: expected $expected, got UNHEALTHY (server $serverId)"
                                    )
                                )
                        }
                    }
                }
        }
        yield()
        sendCommand()
        try {
            withTimeoutOrNull(timeout) { found.await() }
                ?: throw ContainerLifecycleException(
                    "step timed out after $timeout waiting for $expected (server $serverId)"
                )
        } finally {
            job.cancel()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun ensureStartable(server: ServerView) {
        if (server.isDisabled()) throw ConflictException(server.disabledReason())
    }

    private fun deriveImage(serverType: ServerType, tag: String) = images.deriveImage(serverType, tag)

    private fun send(nodeId: String, msg: MasterMessage): Boolean = gateway.sendToNode(nodeId, msg)
}

class ContainerLifecycleException(message: String) : Exception(message)

/** Fixed JVM non-heap cost (Metaspace, thread stacks, code cache, entrypoint) reserved on top of the heap. */
private const val NON_HEAP_BASE_MB = 512

/** Per-mille of the container additionally reserved for heap-scaling non-heap memory (GC structures, direct buffers). */
private const val NON_HEAP_RATIO_PER_MILLE = 125

/**
 * Default heap size (MB) for a container with [memoryMb] of cgroup memory, emitted as itzg's
 * `MEMORY` env var.
 *
 * Deliberately non-linear. JVM non-heap memory is a roughly fixed cost plus a slice that grows
 * with the heap, so a single flat percentage fails at both ends: the JVM's own 25% default
 * starves big servers, while a high flat rate (previously 75%) leaves small containers no
 * headroom — Aikar's flags set `-Xms=-Xmx=MEMORY` with `AlwaysPreTouch`, committing the whole
 * heap at boot, so an over-large heap gets the container OOMKilled before the JVM can log.
 *
 * Reserves `NON_HEAP_BASE_MB + 12.5%` of the container for non-heap, capped at half the
 * container so tiny servers keep a usable heap. Net effect: heap is 50% for the smallest
 * servers and approaches ~87.5% as memory grows. A user-supplied `MEMORY` env var still wins
 * on collision (see `buildAllVars`).
 */
internal fun defaultHeapMb(memoryMb: Int): Int {
    if (memoryMb <= 0) return 0
    val reservedMb = NON_HEAP_BASE_MB + memoryMb * NON_HEAP_RATIO_PER_MILLE / 1000
    val overheadMb = minOf(reservedMb, memoryMb / 2)
    return memoryMb - overheadMb
}
