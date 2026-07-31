package io.craftpanel.master.grpc

import io.craftpanel.master.config.NodeConfig
import io.craftpanel.master.database.entity.Node
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.domain.NodeStatus
import io.craftpanel.master.service.repo.NodeRepository
import io.craftpanel.master.service.repo.NodeRow
import io.craftpanel.master.util.CryptoUtils
import io.craftpanel.master.util.toUtcString
import io.craftpanel.proto.IdentifyNodeRequest
import io.craftpanel.proto.IdentifyNodeResponse
import io.craftpanel.proto.RegisterNodeRequest
import io.craftpanel.proto.RegisterNodeResponse
import io.craftpanel.proto.identifyNodeResponse
import io.craftpanel.proto.registerNodeResponse
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Node registration, identity, and bootstrap/key auth. Owns the Nodes table's write path. */
class NodeRegistrar(private val nodeConfig: NodeConfig, private val nodeRepository: NodeRepository) {

    private val log = LoggerFactory.getLogger(NodeRegistrar::class.java)

    companion object {

        // Mirrors Nodes.portRangeStart/portRangeEnd column defaults — actual range is assigned
        // by an admin at node-approval time (trustNode), not at registration.
        private const val DEFAULT_PORT_RANGE_START = 25570
        private const val DEFAULT_PORT_RANGE_END = 26070
    }

    suspend fun registerNode(request: RegisterNodeRequest): RegisterNodeResponse {
        require(
            MessageDigest.isEqual(
                request.bootstrapToken.toByteArray(Charsets.UTF_8),
                nodeConfig.bootstrapToken.toByteArray(Charsets.UTF_8)
            )
        ) { "Invalid bootstrap token" }

        val rawKey = generateNodeKey()
        val keyHash = sha256Hex(rawKey)
        val meta = request.metadata
        val now = Clock.System.now()

        val created = transaction {
            val e = Node.new {
                this.displayName = meta.hostname
                this.hostname = meta.hostname
                this.publicIp = meta.publicIp
                this.privateIp = meta.privateIp
                this.tokenHash = keyHash
                this.portRangeStart = DEFAULT_PORT_RANGE_START
                this.portRangeEnd = DEFAULT_PORT_RANGE_END
                this.totalRamMb = meta.totalRamMb
                this.totalCpuShares = meta.totalCpuShares
                this.agentVersion = meta.agentVersion.takeIf { v -> v.isNotEmpty() }
                this.lastSeenAt = now.toLocalDateTime(TimeZone.UTC)
            }
            val row = Nodes.selectAll().where { Nodes.id eq e.id }.first()
            NodeRow(
                id = row[Nodes.id].value,
                displayName = row[Nodes.displayName],
                hostname = row[Nodes.hostname],
                publicIp = row[Nodes.publicIp],
                privateIp = row[Nodes.privateIp],
                tokenHash = row[Nodes.tokenHash],
                status = row[Nodes.status],
                health = row[Nodes.health],
                totalRamMb = row[Nodes.totalRamMb],
                totalCpuShares = row[Nodes.totalCpuShares],
                systemRamUsedMb = row[Nodes.systemRamUsedMb],
                reservedRamMb = row[Nodes.reservedRamMb],
                portRangeStart = row[Nodes.portRangeStart],
                portRangeEnd = row[Nodes.portRangeEnd],
                swarmActive = row[Nodes.swarmActive],
                agentVersion = row[Nodes.agentVersion],
                lastSeenAt = row[Nodes.lastSeenAt]?.toUtcString(),
                createdAt = row[Nodes.createdAt].toUtcString(),
                updatedAt = row[Nodes.updatedAt].toUtcString()
            )
        }

        log.info("Node registered: ${created.id} (${meta.hostname}) — status PENDING, awaiting admin approval")
        return registerNodeResponse {
            nodeKey = rawKey
            nodeId = created.id.toString()
        }
    }

    suspend fun identifyNode(request: IdentifyNodeRequest): IdentifyNodeResponse {
        val keyHash = sha256Hex(request.nodeKey)
        val now = Clock.System.now()

        val existing = nodeRepository.findByTokenHash(keyHash)
        if (existing != null) {
            transaction {
                Node.findById(existing.id)?.let {
                    it.lastSeenAt = now.toLocalDateTime(TimeZone.UTC)
                    it.publicIp = request.metadata.publicIp
                    if (request.metadata.agentVersion.isNotEmpty()) it.agentVersion = request.metadata.agentVersion
                    it.privateIp = request.metadata.privateIp
                    if (request.metadata.hostname.isNotEmpty()) it.hostname = request.metadata.hostname
                }
            }
        }

        val identifyStatus = when (existing?.let { NodeStatus.fromDb(it.status) }) {
            NodeStatus.ACTIVE -> IdentifyNodeResponse.IdentifyStatus.ACTIVE
            NodeStatus.PENDING -> IdentifyNodeResponse.IdentifyStatus.PENDING
            else -> IdentifyNodeResponse.IdentifyStatus.REJECTED
        }

        val rowId = existing?.id
            ?.toString() ?: ""
        log.info("Node identified: $rowId — $identifyStatus")
        return identifyNodeResponse {
            status = identifyStatus
            nodeId = rowId
        }
    }

    /** Throws PERMISSION_DENIED if the node isn't ACTIVE in the DB. Called on first control-stream message. */
    fun requireActive(nodeId: String) {
        val nodeStatus = nodeRepository.findById(Uuid.parse(nodeId))?.status
        log.info("Node $nodeId: first message, db status=$nodeStatus")
        if (nodeStatus != "ACTIVE") {
            val reason = when (nodeStatus) {
                "PENDING" -> "Node $nodeId is pending admin approval"
                "REJECTED" -> "Node $nodeId has been rejected"
                "DECOMMISSIONED" -> "Node $nodeId has been decommissioned"
                else -> "Node $nodeId is not authorized to connect"
            }
            throw StatusException(Status.PERMISSION_DENIED.withDescription(reason))
        }
    }

    /** Verify a node key from a bulk transfer auth header against the DB. */
    fun verifyNodeKey(rawNodeKey: String): Boolean {
        val hash = sha256Hex(rawNodeKey)
        return nodeRepository.findByTokenHash(hash)?.status == "ACTIVE"
    }

    fun generateNodeKey(): String = CryptoUtils.generateToken(32)

    private fun sha256Hex(input: String): String = HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray())
        )
}
