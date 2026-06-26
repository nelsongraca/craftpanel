package io.craftpanel.master.service

import io.craftpanel.master.config.ImagesConfig
import io.craftpanel.master.database.schema.ServerEnvVars
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.proto.MasterMessage
import io.craftpanel.proto.masterMessage
import io.craftpanel.proto.removeContainerCommand
import io.craftpanel.proto.startContainerCommand
import io.craftpanel.proto.stopContainerCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class ContainerLifecycle(
    private val gateway: AgentGateway,
    private val modService: ModService,
    private val images: ImagesConfig = ImagesConfig("itzg/minecraft-server", "itzg/mc-proxy"),
    private val containerNamePrefix: String = "craftpanel",
    private val clock: Clock = Clock.System,
    private val stopTimeout: Duration = 45.seconds,
    private val startTimeout: Duration = 30.seconds,
    private val removeTimeout: Duration = 10.seconds,
) {

    // ── Fire-and-forget (used by ServerService route handlers) ────────────────

    fun sendStart(server: ResultRow, needsRecreate: Boolean, publicHostname: String? = null, nodeId: String = server[Servers.nodeId].toString()) {
        sendOrThrow(nodeId, buildStartMessage(server, needsRecreate, publicHostname, nodeId))
    }

    fun sendStop(server: ResultRow, nodeId: String) {
        val id = server[Servers.id]
        sendOrThrow(nodeId, masterMessage {
            stopContainer = stopContainerCommand {
                serverId = id.toString()
                containerName = "$containerNamePrefix-$id"
                timeoutSeconds = 30
                stopCommand = server[Servers.stopCommand]
            }
        })
    }

    fun sendRemove(server: ResultRow, nodeId: String, force: Boolean = false) {
        val id = server[Servers.id]
        sendOrThrow(nodeId, masterMessage {
            removeContainer = removeContainerCommand {
                serverId = id.toString()
                containerName = "$containerNamePrefix-$id"
                this.force = force
            }
        })
    }

    // ── Public compound operations (with await, used by MigrationService) ─────

    suspend fun start(server: ResultRow, needsRecreate: Boolean, publicHostname: String? = null, nodeId: String = server[Servers.nodeId].toString()) {
        val id = server[Servers.id]
        awaitStatus(id.toString(), ServerStatus.HEALTHY, startTimeout) {
            sendStart(server, needsRecreate, publicHostname, nodeId)
        }
        writeStatus(id, ServerStatus.HEALTHY, clearNeedsRecreate = true)
    }

    // ── Public primitives (used by MigrationService for cross-node relocation) ─

    suspend fun stop(server: ResultRow, nodeId: String) {
        val id = server[Servers.id]
        awaitStatus(id.toString(), ServerStatus.STOPPED, stopTimeout) {
            sendStop(server, nodeId)
        }
    }

    suspend fun remove(server: ResultRow, nodeId: String, force: Boolean = false) {
        val id = server[Servers.id]
        awaitStatus(id.toString(), ServerStatus.STOPPED, removeTimeout) {
            sendRemove(server, nodeId, force)
        }
    }

    // ── Build helpers ─────────────────────────────────────────────────────────

    fun buildStartMessage(server: ResultRow, needsRecreate: Boolean, publicHostname: String? = null, nodeId: String = server[Servers.nodeId].toString()): MasterMessage {
        val id = server[Servers.id]
        val image = deriveImage(server[Servers.serverType], server[Servers.itzgImageTag])
        val allVars = buildAllVars(id, server)
        // Always provide a routing hostname so mc-router can label the container and
        // the agent's MetricsCollector can ping it for player-count collection.
        val resolvedHostname = publicHostname ?: server[Servers.dnsRecordName] ?: "$id.mc.internal"
        return masterMessage {
            startContainer = startContainerCommand {
                serverId = id.toString()
                containerName = "$containerNamePrefix-$id"
                stopCommand = server[Servers.stopCommand]
                this.needsRecreate = needsRecreate
                this.image = image
                envVars.putAll(allVars)
                this.publicHostname = resolvedHostname
                hostPort = server[Servers.hostPort]
                memoryMb = server[Servers.memoryMb]
                cpuShares = server[Servers.cpuShares]
                dockerNetwork = server[Servers.networkId]
                    ?.let { "$containerNamePrefix-net-$it" }
                    ?: "$containerNamePrefix-server-$id"
            }
        }
    }

    fun buildAllVars(id: Uuid, server: ResultRow): Map<String, String> {
        val serverType = server[Servers.serverType]
        val modrinthProjects = modService.buildModrinthEnvVar(id)
        val dbEnvVars = transaction {
            ServerEnvVars.selectAll()
                .where { ServerEnvVars.serverId eq id }
                .associate { it[ServerEnvVars.key] to it[ServerEnvVars.value] }
        }
        val systemVars = buildMap {
            put("EULA", "TRUE")
            put("TYPE", serverType)
            put("VERSION", server[Servers.mcVersion])
            put("MEMORY", "${server[Servers.memoryMb]}M")
            if (modrinthProjects.isNotEmpty()) put("MODRINTH_PROJECTS", modrinthProjects)
        }
        return systemVars + dbEnvVars
    }

    /**
     * Fire-and-forget restart of a server that crashed, issued by the master crash-recovery path.
     * Reuses the normal start command (no recreate) and marks the server STARTING so the next
     * status report reconciles it. Safe no-op if the row is gone.
     */
    fun restartCrashedServer(id: Uuid) {
        val server = transaction {
            Servers.selectAll()
                .where { Servers.id eq id }
                .firstOrNull()
        } ?: return
        writeStatus(id, ServerStatus.STARTING)
        sendStart(server, needsRecreate = false)
    }

    fun writeStatus(id: Uuid, status: ServerStatus, clearNeedsRecreate: Boolean = false) {
        transaction {
            Servers.update({ Servers.id eq id }) {
                it[Servers.status] = status.toDb()
                if (clearNeedsRecreate) it[Servers.needsRecreate] = false
                it[Servers.updatedAt] = clock.now()
                    .toLocalDateTime(TimeZone.UTC)
            }
        }
    }

    // ── Core await primitive ──────────────────────────────────────────────────

    private suspend fun awaitStatus(
        serverId: String,
        expected: ServerStatus,
        timeout: Duration,
        sendCommand: () -> Unit,
    ): Unit = coroutineScope {
        val found = CompletableDeferred<Unit>()
        val job = launch {
            gateway.agentEvents
                .filterIsInstance<AgentEvent.ServerStatusEvent>()
                .collect { event ->
                    if (event.serverId == serverId) {
                        when {
                            event.status == expected               ->
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
        }
        finally {
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
