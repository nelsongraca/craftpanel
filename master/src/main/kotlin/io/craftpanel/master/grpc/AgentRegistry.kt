package io.craftpanel.master.grpc

import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.service.AgentGateway
import io.craftpanel.master.service.repo.BackupRepository
import io.craftpanel.master.service.repo.ServerRepository
import io.craftpanel.master.util.formatSymlinkTimestamp
import io.craftpanel.proto.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * The agent connection registry and outbound gateway: owns the node→outbound-channel map and the
 * send/emit surface every service depends on. Extracted from [ControlServiceImpl] so the gRPC
 * transport does not also own gateway state.
 */
class AgentRegistry(
    private val agentEventsFlow: MutableSharedFlow<AgentEvent>,
    private val serverRepository: ServerRepository,
    private val backupRepository: BackupRepository
) : AgentGateway {

    private val log = LoggerFactory.getLogger(AgentRegistry::class.java)

    private val connectedAgents = ConcurrentHashMap<String, SendChannel<MasterMessage>>()

    override val agentEvents: SharedFlow<AgentEvent> = agentEventsFlow.asSharedFlow()

    /** Registers the authenticated control stream's outbound channel for [nodeId]. */
    fun register(nodeId: String, channel: SendChannel<MasterMessage>) {
        connectedAgents[nodeId] = channel
        log.debug("Node $nodeId: registered in AgentRegistry (channel=${System.identityHashCode(channel)})")
    }

    /**
     * Removes [channel] for [nodeId] only when it is still the owner (guards against a newer
     * connection being clobbered by a stale stream's teardown). Returns true when this call removed it.
     */
    fun deregister(nodeId: String, channel: SendChannel<MasterMessage>): Boolean = connectedAgents.remove(nodeId, channel)

    fun isConnected(nodeId: String): Boolean = connectedAgents.containsKey(nodeId)

    /** Emit an event onto the shared bus (alert firings, node health changes). */
    suspend fun emit(event: AgentEvent) {
        agentEventsFlow.emit(event)
    }

    override fun sendToNode(nodeId: String, msg: MasterMessage): Boolean {
        val channel = connectedAgents[nodeId]
        if (channel == null) {
            log.warn("sendToNode: node {} not found in connectedAgents (connected: {})", nodeId, connectedAgents.keys)
            return false
        }
        return channel.trySend(msg).isSuccess
    }

    override suspend fun sendToNodeSuspending(nodeId: String, msg: MasterMessage): Boolean {
        val channel = connectedAgents[nodeId]
        if (channel == null) {
            log.warn("sendToNodeSuspending: node {} not found in connectedAgents (connected: {})", nodeId, connectedAgents.keys)
            return false
        }
        return try {
            channel.send(msg)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("sendToNodeSuspending: node {} send failed — {}", nodeId, e.message)
            false
        }
    }

    /**
     * Reconnect self-heal: after a node reconciles its state snapshot, push the full
     * symlink-overlay mapping (servers-by-name + backups-by-server) so the agent can
     * rebuild both trees from canonical storage. Deliberately conservative — only ever
     * adds symlinks; never prunes. See server-path-navigation plan, Task 4.
     */
    override fun rebuildSymlinks(nodeId: String) {
        val kotlinNodeId = runCatching { Uuid.parse(nodeId) }.getOrNull() ?: return
        runCatching { buildRebuildSymlinksCommand(kotlinNodeId) }
            .onSuccess { command -> sendToNode(nodeId, command) }
            .onFailure { e -> log.error("Node $nodeId: failed to send RebuildSymlinksCommand — ${e.message}", e) }
    }

    @OptIn(com.google.protobuf.kotlin.OnlyForUseByGeneratedProtoCode::class)
    internal fun buildRebuildSymlinksCommand(nodeId: Uuid): MasterMessage {
        val servers = serverRepository.listByNodeId(nodeId)
        val backups = servers.flatMap { server ->
            backupRepository.listBackups(server.id)
                .filter { it.status == "COMPLETED" && !it.filePath.isNullOrEmpty() }
                .map { server to it }
        }
        val builder = RebuildSymlinksCommand.newBuilder()
        servers.forEach { server ->
            builder.addServersBuilder()
                .setServerId(server.id.toString())
                .setServerName(server.name)
                .setDataDirName(server.dataDirName ?: "")
        }
        backups.forEach { (server, backup) ->
            builder.addBackupsBuilder()
                .setBackupId(backup.id.toString())
                .setServerId(server.id.toString())
                .setServerName(server.name)
                .setCreatedAtFormatted(formatSymlinkTimestamp(backup.createdAt))
                .setFilePath(backup.filePath ?: "")
        }
        return masterMessage { rebuildSymlinks = builder.build() }
    }
}
