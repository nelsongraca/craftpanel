package io.craftpanel.master.grpc

import com.craftpanel.agent.v1.*
import io.craftpanel.master.config.NodeConfig
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.util.toKotlinUuid
import java.util.UUID
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class ControlServiceImpl(private val nodeConfig: NodeConfig) :
    ControlServiceGrpcKt.ControlServiceCoroutineImplBase() {

    private val log = LoggerFactory.getLogger(ControlServiceImpl::class.java)
    private val random = SecureRandom()
    private val connectedAgents = ConcurrentHashMap<String, SendChannel<MasterMessage>>()

    fun sendToNode(nodeId: String, msg: MasterMessage): Boolean {
        val channel = connectedAgents[nodeId] ?: return false
        return channel.trySend(msg).isSuccess
    }

    override suspend fun registerNode(request: RegisterNodeRequest): RegisterNodeResponse {
        require(request.bootstrapToken == nodeConfig.bootstrapToken) {
            "Invalid bootstrap token"
        }

        val rawKey = generateNodeKey()
        val keyHash = sha256Hex(rawKey)
        val meta = request.metadata
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

        val generatedId = transaction {
            Nodes.insert {
                it[displayName] = meta.hostname
                it[hostname] = meta.hostname
                it[publicIp] = meta.publicIp
                it[privateIp] = meta.privateIp
                it[tokenHash] = keyHash
                it[status] = "PENDING"
                it[totalRamMb] = meta.totalRamMb
                it[totalCpuShares] = meta.totalCpuShares
                it[agentVersion] = meta.agentVersion.takeIf { it.isNotEmpty() }
                it[lastSeenAt] = now
            }[Nodes.id]
        }

        log.info("Node registered: $generatedId (${meta.hostname}) — status PENDING, awaiting admin approval")
        return registerNodeResponse {
            nodeKey = rawKey
            nodeId = generatedId.toString()
        }
    }

    override suspend fun identifyNode(request: IdentifyNodeRequest): IdentifyNodeResponse {
        val keyHash = sha256Hex(request.nodeKey)
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

        val row = transaction {
            val r = Nodes.selectAll()
                .where { Nodes.tokenHash eq keyHash }
                .firstOrNull()

            if (r != null) {
                Nodes.update({ Nodes.tokenHash eq keyHash }) {
                    it[lastSeenAt] = now
                    it[publicIp] = request.metadata.publicIp
                    it[privateIp] = request.metadata.privateIp
                    it[agentVersion] = request.metadata.agentVersion.takeIf { it.isNotEmpty() }
                }
            }
            r
        }

        val identifyStatus = when (row?.get(Nodes.status)) {
            "ACTIVE" -> IdentifyNodeResponse.IdentifyStatus.ACTIVE
            "PENDING" -> IdentifyNodeResponse.IdentifyStatus.PENDING
            else -> IdentifyNodeResponse.IdentifyStatus.REJECTED
        }

        val rowId = row?.get(Nodes.id)?.toString() ?: ""
        log.info("Node identified: $rowId — $identifyStatus")
        return identifyNodeResponse {
            status = identifyStatus
            nodeId = rowId
        }
    }

    override fun control(requests: Flow<AgentMessage>): Flow<MasterMessage> = channelFlow {
        var connectedNodeId: String? = null
        val outChannel = this.channel

        try {
            requests.collect { msg ->
                if (connectedNodeId == null) {
                    connectedNodeId = msg.nodeId
                    connectedAgents[msg.nodeId] = outChannel
                }

                when {
                    msg.hasNodeState() -> {
                        log.info("Node ${msg.nodeId} sent state snapshot with ${msg.nodeState.containersCount} containers")
                        reconcileNodeState(msg.nodeId, msg.nodeState)
                    }
                    msg.hasNodeMetrics() -> {
                        persistNodeMetrics(msg.nodeId, msg.nodeMetrics)
                    }
                    msg.hasServerStatus() -> {
                        log.debug("Node ${msg.nodeId} server status: ${msg.serverStatus.serverId} → ${msg.serverStatus.status}")
                        persistServerStatus(msg.serverStatus)
                    }
                    msg.hasPlayerUpdate() -> {
                        log.debug("Node ${msg.nodeId} player update: ${msg.playerUpdate.serverId} — ${msg.playerUpdate.playerCount} players")
                    }
                    else -> log.debug("Node ${msg.nodeId} sent unhandled message type")
                }
            }
        } finally {
            connectedNodeId?.let { nodeId -> connectedAgents.remove(nodeId, outChannel) }
        }
    }

    private fun reconcileNodeState(nodeId: String, snapshot: NodeStateSnapshot) {
        val kotlinNodeId = runCatching { UUID.fromString(nodeId).toKotlinUuid() }.getOrNull() ?: return
        transaction {
            val byServerId = snapshot.containersList.associateBy { it.serverId }
            Servers.selectAll().where { Servers.nodeId eq kotlinNodeId }.forEach { server ->
                val serverId = server[Servers.id]
                val container = byServerId[serverId.toString()]
                val newStatus = when (container?.runState) {
                    ContainerState.RunState.RUNNING -> "RUNNING"
                    ContainerState.RunState.STOPPED -> "STOPPED"
                    ContainerState.RunState.EXITED -> "ERROR"
                    null -> null
                    else -> null
                } ?: return@forEach
                Servers.update({ Servers.id eq serverId }) {
                    it[Servers.status] = newStatus
                    it[Servers.containerId] = container?.containerId?.takeIf { s -> s.isNotEmpty() }
                }
            }
        }
    }

    private fun persistNodeMetrics(nodeId: String, metrics: NodeMetricsUpdate) {
        // TODO: write NodeMetrics row
    }

    private fun persistServerStatus(update: ServerStatusUpdate) {
        val serverId = runCatching { UUID.fromString(update.serverId).toKotlinUuid() }.getOrNull() ?: return
        val dbStatus = when (update.status) {
            ServerStatusUpdate.ServerStatus.STARTING -> "STARTING"
            ServerStatusUpdate.ServerStatus.HEALTHY -> "RUNNING"
            ServerStatusUpdate.ServerStatus.STOPPED -> "STOPPED"
            ServerStatusUpdate.ServerStatus.UNHEALTHY -> "ERROR"
            else -> return
        }
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        transaction {
            Servers.update({ Servers.id eq serverId }) {
                it[Servers.status] = dbStatus
                it[Servers.containerId] = update.containerId.takeIf { s -> s.isNotEmpty() }
                it[Servers.lastSeenAt] = now
            }
        }
    }

    fun generateNodeKey(): String {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
