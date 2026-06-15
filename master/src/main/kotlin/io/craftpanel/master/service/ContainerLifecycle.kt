package io.craftpanel.master.service

import io.craftpanel.master.config.ImagesConfig
import io.craftpanel.master.database.schema.ServerEnvVars
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.proto.MasterMessage
import io.craftpanel.proto.createContainerCommand
import io.craftpanel.proto.masterMessage
import io.craftpanel.proto.removeContainerCommand
import io.craftpanel.proto.startContainerCommand
import io.craftpanel.proto.stopContainerCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharedFlow
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class ContainerLifecycle(
    private val sendToNode: (String, MasterMessage) -> Boolean,
    private val agentEvents: SharedFlow<AgentEvent>,
    private val modService: ModService,
    private val images: ImagesConfig = ImagesConfig("itzg/minecraft-server", "itzg/mc-proxy"),
    private val containerNamePrefix: String = "craftpanel",
    private val clock: Clock = Clock.System,
    private val createTimeout: Duration = 10.minutes,
    private val stopTimeout: Duration = 45.seconds,
    private val startTimeout: Duration = 30.seconds,
    private val removeTimeout: Duration = 10.seconds,
) {

    // ── Public compound operations ────────────────────────────────────────────

    suspend fun start(server: ResultRow, pull: Boolean, publicHostname: String? = null) {
        val id = server[Servers.id]
        val nodeId = server[Servers.nodeId].toString()

        if (pull || server[Servers.containerId] == null) {
            if (server[Servers.containerId] != null) {
                remove(server, nodeId)
            }
            create(server, nodeId, publicHostname)
        }

        sendStart(server, nodeId)
        writeStatus(id, ServerStatus.STARTING, clearNeedsRecreate = true)
    }

    suspend fun recreate(server: ResultRow, hostnameOverride: String?) {
        val id = server[Servers.id]
        val nodeId = server[Servers.nodeId].toString()

        stop(server, nodeId)
        remove(server, nodeId)
        create(server, nodeId, hostnameOverride)
        sendStart(server, nodeId)

        writeStatus(id, ServerStatus.STOPPING, clearNeedsRecreate = true)
    }

    // ── Public primitives (used by MigrationService for cross-node relocation) ─

    suspend fun stop(server: ResultRow, nodeId: String) {
        val id = server[Servers.id]
        awaitStatus(id.toString(), ServerStatus.STOPPED, stopTimeout) {
            sendOrThrow(nodeId, masterMessage {
                stopContainer = stopContainerCommand {
                    serverId = id.toString()
                    containerName = "$containerNamePrefix-$id"
                    timeoutSeconds = 30
                    stopCommand = server[Servers.stopCommand]
                }
            })
        }
    }

    suspend fun remove(server: ResultRow, nodeId: String, force: Boolean = false) {
        val id = server[Servers.id]
        awaitStatus(id.toString(), ServerStatus.STOPPED, removeTimeout) {
            sendOrThrow(nodeId, masterMessage {
                removeContainer = removeContainerCommand {
                    serverId = id.toString()
                    containerName = "$containerNamePrefix-$id"
                    this.force = force
                }
            })
        }
    }

    suspend fun create(server: ResultRow, nodeId: String, publicHostname: String? = null) {
        val id = server[Servers.id]
        val image = deriveImage(server[Servers.serverType], server[Servers.itzgImageTag])
        val allVars = buildAllVars(id, server)
        val resolvedHostname = publicHostname ?: server[Servers.dnsRecordName]
        awaitStatus(id.toString(), ServerStatus.STOPPED, createTimeout) {
            sendOrThrow(nodeId, buildCreate(id, server, image, allVars, resolvedHostname))
        }
    }

    suspend fun sendStart(server: ResultRow, nodeId: String) {
        val id = server[Servers.id]
        awaitStatus(id.toString(), ServerStatus.HEALTHY, startTimeout) {
            sendOrThrow(nodeId, masterMessage {
                startContainer = startContainerCommand {
                    serverId = id.toString()
                    containerName = "$containerNamePrefix-$id"
                }
            })
        }
    }

    // ── Build helpers ─────────────────────────────────────────────────────────

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
            put("ENABLE_QUERY", "TRUE")
            if (modrinthProjects.isNotEmpty()) put("MODRINTH_PROJECTS", modrinthProjects)
        }
        return systemVars + dbEnvVars
    }

    fun buildCreate(
        id: Uuid,
        server: ResultRow,
        image: String,
        allVars: Map<String, String>,
        publicHostname: String?,
    ): MasterMessage = masterMessage {
        createContainer = createContainerCommand {
            serverId = id.toString()
            containerName = "$containerNamePrefix-$id"
            this.image = image
            ramMb = server[Servers.memoryMb]
            cpuShares = server[Servers.cpuShares]
            hostPort = server[Servers.hostPort]
            envVars.putAll(allVars)
            dockerNetwork = server[Servers.networkId]?.let { "$containerNamePrefix-net-$it" } ?: ""
            restartPolicy = "unless-stopped"
            stopCommand = server[Servers.stopCommand]
            mcRouterHostname = publicHostname ?: ""
        }
    }

    fun writeStatus(id: Uuid, status: ServerStatus, clearNeedsRecreate: Boolean = false) {
        transaction {
            Servers.update({ Servers.id eq id }) {
                it[Servers.status] = status.toDb()
                if (clearNeedsRecreate) it[Servers.needsRecreate] = false
                it[Servers.updatedAt] = clock.now().toLocalDateTime(TimeZone.UTC)
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
            agentEvents
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
        if (!sendToNode(nodeId, msg)) throw BadGatewayException("Agent not connected")
    }
}

class ContainerLifecycleException(message: String) : Exception(message)
