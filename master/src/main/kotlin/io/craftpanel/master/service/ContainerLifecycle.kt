package io.craftpanel.master.service

import io.craftpanel.master.config.ImagesConfig
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.service.repo.*
import io.craftpanel.proto.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.filterIsInstance
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class ContainerLifecycle(
    private val gateway: AgentGateway,
    private val modService: ModService,
    private val serverRepository: ServerRepository,
    private val envVarsRepository: EnvVarsRepository,
    private val images: ImagesConfig = ImagesConfig("itzg/minecraft-server", "itzg/mc-proxy"),
    private val containerNamePrefix: String = "craftpanel",
    private val clock: Clock = Clock.System,
    private val stopTimeout: Duration = 45.seconds,
    private val startTimeout: Duration = 30.seconds,
    private val removeTimeout: Duration = 10.seconds
) {

    // ── Fire-and-forget (used by ServerService route handlers) ────────────────

    fun sendStart(server: ServerRow, needsRecreate: Boolean, publicHostname: String? = null, nodeId: String = server.nodeId.toString()) {
        sendOrThrow(nodeId, buildStartMessage(server, needsRecreate, publicHostname, nodeId))
    }

    fun sendStop(server: ServerRow, nodeId: String) {
        val id = server.id
        sendOrThrow(
            nodeId,
            masterMessage {
                stopContainer = stopContainerCommand {
                    serverId = id.toString()
                    containerName = "$containerNamePrefix-$id"
                    timeoutSeconds = 30
                    stopCommand = server.stopCommand
                }
            }
        )
    }

    fun sendRemove(server: ServerRow, nodeId: String, force: Boolean = false) {
        val id = server.id
        sendOrThrow(
            nodeId,
            masterMessage {
                removeContainer = removeContainerCommand {
                    serverId = id.toString()
                    containerName = "$containerNamePrefix-$id"
                    this.force = force
                }
            }
        )
    }

    // ── Public compound operations (with await, used by MigrationService) ─────

    suspend fun start(server: ServerRow, needsRecreate: Boolean, publicHostname: String? = null, nodeId: String = server.nodeId.toString()) {
        val id = server.id
        awaitStatus(id.toString(), ServerStatus.HEALTHY, startTimeout) {
            sendStart(server, needsRecreate, publicHostname, nodeId)
        }
        writeStatus(id, ServerStatus.HEALTHY, clearNeedsRecreate = true)
    }

    // ── Public primitives (used by MigrationService for cross-node relocation) ─

    suspend fun stop(server: ServerRow, nodeId: String) {
        val id = server.id
        awaitStatus(id.toString(), ServerStatus.STOPPED, stopTimeout) {
            sendStop(server, nodeId)
        }
    }

    suspend fun remove(server: ServerRow, nodeId: String, force: Boolean = false) {
        val id = server.id
        awaitStatus(id.toString(), ServerStatus.STOPPED, removeTimeout) {
            sendRemove(server, nodeId, force)
        }
    }

    // ── Build helpers ─────────────────────────────────────────────────────────

    fun buildStartMessage(server: ServerRow, needsRecreate: Boolean, publicHostname: String? = null, nodeId: String = server.nodeId.toString()): MasterMessage {
        val id = server.id
        val image = deriveImage(server.serverType, server.itzgImageTag)
        val allVars = buildAllVars(server)
        val resolvedHostname = publicHostname ?: server.dnsRecordName ?: "$id.mc.internal"
        return masterMessage {
            startContainer = startContainerCommand {
                serverId = id.toString()
                containerName = "$containerNamePrefix-$id"
                stopCommand = server.stopCommand
                this.needsRecreate = needsRecreate
                this.image = image
                envVars.putAll(allVars)
                this.publicHostname = resolvedHostname
                hostPort = server.hostPort
                memoryMb = server.memoryMb
                cpuShares = server.cpuShares
                dockerNetwork = server.networkId
                    ?.let { "$containerNamePrefix-net-$it" }
                    ?: "$containerNamePrefix-server-$id"
            }
        }
    }

    private fun buildAllVars(server: ServerRow): Map<String, String> {
        val id = server.id
        val modrinthProjects = modService.buildModrinthEnvVar(id)
        val dbEnvVars = envVarsRepository.getEnvVars(id)
            .associate { it.key to it.value }
        val systemVars = buildMap {
            put("EULA", "TRUE")
            put("TYPE", server.serverType)
            put("VERSION", server.mcVersion)
            put("MEMORY", "${server.memoryMb}M")
            if (modrinthProjects.isNotEmpty()) put("MODRINTH_PROJECTS", modrinthProjects)
        }
        return systemVars + dbEnvVars
    }

    fun startCrashRestartLoop(scope: CoroutineScope, channel: ReceiveChannel<Uuid>) {
        scope.launch {
            for (id in channel) {
                val server = serverRepository.findById(id) ?: continue
                writeStatus(id, ServerStatus.STARTING)
                sendStart(server, needsRecreate = false)
            }
        }
    }

    fun writeStatus(id: Uuid, status: ServerStatus, clearNeedsRecreate: Boolean = false) {
        serverRepository.updateStatus(id, status.toDb(), null)
        if (clearNeedsRecreate) serverRepository.updateNeedsRecreate(id, false)
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

    private fun deriveImage(serverType: String, tag: String) = images.deriveImage(serverType, tag)

    private fun sendOrThrow(nodeId: String, msg: MasterMessage) {
        if (!gateway.sendToNode(nodeId, msg)) throw BadGatewayException("Agent not connected")
    }
}

class ContainerLifecycleException(message: String) : Exception(message)
