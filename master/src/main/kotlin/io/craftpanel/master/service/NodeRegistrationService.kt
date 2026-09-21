package io.craftpanel.master.service

import io.craftpanel.master.config.NodeConfig
import io.craftpanel.master.database.entity.Node
import io.craftpanel.master.domain.NodeStatus
import io.craftpanel.master.service.repo.NodeRepository
import io.craftpanel.master.util.CryptoUtils
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** The node metadata an agent reports at registration and on every reconnect. */
data class NodeMetadata(
    val hostname: String,
    val publicIp: String,
    val privateIp: String,
    val totalRamMb: Int,
    val reservedRamMb: Int,
    val totalCpuMillicores: Int,
    val reservedCpuMillicores: Int,
    val agentVersion: String
)

/** A freshly registered node: its id plus the raw node key to hand back to the agent (never stored raw). */
data class RegisteredNode(val nodeId: Uuid, val rawKey: String)

/** The outcome of identifying a node: its status, and its id when the key matched a known node. */
data class IdentifiedNode(val status: NodeStatus, val nodeId: Uuid?)

/** Thrown by [NodeRegistrationService.requireActive] when a node is not `ACTIVE`. Mapped to gRPC by the transport. */
class NodeNotActiveException(message: String) : Exception(message)

/**
 * The one owner of node identity: registration, identification, active-node enforcement, and the node
 * key minting/hashing. Reads via [NodeRepository], writes via Exposed entities inside its own
 * transaction (ADR-0004); the gRPC transport maps proto at the edge.
 */
class NodeRegistrationService(private val nodeConfig: NodeConfig, private val nodeRepository: NodeRepository) {

    private val log = LoggerFactory.getLogger(NodeRegistrationService::class.java)

    companion object {
        // Mirrors Nodes.portRangeStart/portRangeEnd column defaults — the real range is assigned by an
        // admin at node-approval time (trustNode), not at registration.
        private const val DEFAULT_PORT_RANGE_START = 25570
        private const val DEFAULT_PORT_RANGE_END = 26070
    }

    fun register(bootstrapToken: String, metadata: NodeMetadata): RegisteredNode {
        require(
            MessageDigest.isEqual(
                bootstrapToken.toByteArray(Charsets.UTF_8),
                nodeConfig.bootstrapToken.toByteArray(Charsets.UTF_8)
            )
        ) { "Invalid bootstrap token" }

        val rawKey = mintKey()
        val keyHash = hashKey(rawKey)
        val now = Clock.System.now()

        val nodeId = transaction {
            Node.new {
                this.displayName = metadata.hostname
                this.hostname = metadata.hostname
                this.publicIp = metadata.publicIp
                this.privateIp = metadata.privateIp
                this.tokenHash = keyHash
                this.portRangeStart = DEFAULT_PORT_RANGE_START
                this.portRangeEnd = DEFAULT_PORT_RANGE_END
                this.totalRamMb = metadata.totalRamMb
                this.reservedRamMb = metadata.reservedRamMb
                this.reservedCpuMillicores = metadata.reservedCpuMillicores
                this.totalCpuMillicores = metadata.totalCpuMillicores
                this.agentVersion = metadata.agentVersion.takeIf { it.isNotEmpty() }
                this.lastSeenAt = now.toLocalDateTime(TimeZone.UTC)
            }.id.value
        }

        log.info("Node registered: $nodeId (${metadata.hostname}) — status PENDING, awaiting admin approval")
        return RegisteredNode(nodeId, rawKey)
    }

    fun identify(rawKey: String, metadata: NodeMetadata): IdentifiedNode {
        val keyHash = hashKey(rawKey)
        val now = Clock.System.now()

        val existing = nodeRepository.findByTokenHash(keyHash)
        if (existing != null) {
            transaction {
                Node.findById(existing.id)?.let {
                    it.lastSeenAt = now.toLocalDateTime(TimeZone.UTC)
                    it.publicIp = metadata.publicIp
                    if (metadata.agentVersion.isNotEmpty()) it.agentVersion = metadata.agentVersion
                    it.privateIp = metadata.privateIp
                    if (metadata.hostname.isNotEmpty()) it.hostname = metadata.hostname
                    it.totalRamMb = metadata.totalRamMb
                    it.reservedRamMb = metadata.reservedRamMb
                    it.totalCpuMillicores = metadata.totalCpuMillicores
                    it.reservedCpuMillicores = metadata.reservedCpuMillicores
                }
            }
        }

        val status = when (existing?.let { NodeStatus.fromDb(it.status) }) {
            NodeStatus.ACTIVE -> NodeStatus.ACTIVE
            NodeStatus.PENDING -> NodeStatus.PENDING
            else -> NodeStatus.REJECTED
        }
        log.info("Node identified: ${existing?.id ?: ""} — $status")
        return IdentifiedNode(status, existing?.id)
    }

    /** Throws [NodeNotActiveException] if the node is not `ACTIVE`. Called on the first control-stream message. */
    fun requireActive(nodeId: Uuid) {
        val status = nodeRepository.findById(nodeId)?.status?.let { NodeStatus.fromDb(it) }
        log.info("Node $nodeId: first message, db status=$status")
        if (status != NodeStatus.ACTIVE) throw NodeNotActiveException(inactiveReason(nodeId, status))
    }

    /** Verify a node key from a bulk transfer auth header against the DB. */
    fun isActive(rawKey: String): Boolean = nodeRepository.findByTokenHash(hashKey(rawKey))?.status == NodeStatus.ACTIVE.toDb()

    fun mintKey(): String = CryptoUtils.generateToken(32)

    fun hashKey(raw: String): String = HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray())
        )

    private fun inactiveReason(nodeId: Uuid, status: NodeStatus?): String = when (status) {
        NodeStatus.PENDING -> "Node $nodeId is pending admin approval"
        NodeStatus.REJECTED -> "Node $nodeId has been rejected"
        NodeStatus.DECOMMISSIONED -> "Node $nodeId has been decommissioned"
        else -> "Node $nodeId is not authorized to connect"
    }
}
