package io.craftpanel.master.service

import com.github.dockerjava.api.DockerClient
import io.craftpanel.master.database.entity.Network
import io.craftpanel.master.database.entity.Server
import io.craftpanel.master.database.schema.ServerNetworks
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.util.toUtcString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

@Serializable
data class NetworkResponse(
    val id: String,
    val name: String,
    @SerialName("proxy_port") val proxyPort: Int?,
    val description: String?,
    @SerialName("server_count") val serverCount: Int,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class NetworkServerItem(val id: String, @SerialName("display_name") val displayName: String, @SerialName("server_type") val serverType: String, val status: ServerStatus)

@Serializable
data class NetworkDetailResponse(
    val id: String,
    val name: String,
    @SerialName("proxy_port") val proxyPort: Int?,
    val description: String?,
    @SerialName("server_count") val serverCount: Int,
    val servers: List<NetworkServerItem>,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class CreateNetworkRequest(val name: String, @SerialName("proxy_port") val proxyPort: Int? = null, val description: String? = null)

@Serializable
data class PatchNetworkRequest(val name: String? = null, val description: String? = null)

class NetworkService(
    private val dockerClient: DockerClient? = null,
    private val containerNamePrefix: String = "craftpanel",
    private val networkRepository: NetworkRepository,
    private val serverRepository: ServerRepository,
    private val nodeRepository: NodeRepository,
    userRepository: UserRepository,
    groupRepository: GroupRepository
) {

    private val log = org.slf4j.LoggerFactory.getLogger(NetworkService::class.java)

    private val hasDockerEndpoint: Boolean = dockerClient != null

    private val visibilityResolver = ServerVisibilityResolver(userRepository, groupRepository)

    fun validateCrossNodeAssignment(nodeIds: List<Uuid>) {
        if (!hasDockerEndpoint) {
            throw UnprocessableException(
                "Master is not configured with a Docker endpoint — Swarm mode required for cross-node Server Networks"
            )
        }
        val nonSwarm = nodeRepository.listByIds(nodeIds)
            .filter { !it.swarmActive }
            .map { it.displayName }
        if (nonSwarm.isNotEmpty()) {
            val names = nonSwarm.joinToString(", ")
            throw UnprocessableException(
                "Node(s) $names are not joined to a Swarm — join all nodes to a Swarm before creating cross-node Server Networks"
            )
        }
    }

    private fun createOverlayNetwork(networkId: String) {
        val docker = dockerClient ?: return
        val name = "$containerNamePrefix-net-$networkId"
        runCatching {
            docker.createNetworkCmd()
                .withName(name)
                .withDriver("overlay")
                .withAttachable(true)
                .withLabels(mapOf("craftpanel.managed" to "true"))
                .exec()
            log.info("Created overlay network $name")
        }.onFailure { log.warn("Failed to create overlay network $name: ${it.message}") }
    }

    private fun deleteOverlayNetwork(networkId: String) {
        val docker = dockerClient ?: return
        val name = "$containerNamePrefix-net-$networkId"
        runCatching {
            val nets = docker.listNetworksCmd()
                .withNameFilter(name)
                .exec()
            nets.forEach {
                docker.removeNetworkCmd(it.id)
                    .exec()
            }
            log.info("Deleted overlay network $name")
        }.onFailure { log.warn("Failed to delete overlay network $name: ${it.message}") }
    }

    fun listNetworks(userId: Uuid): List<NetworkResponse> {
        val visibility = visibilityResolver.resolve(userId)
        val networks = when {
            visibility.isGlobal -> networkRepository.listAll()
            visibility.networkIds.isEmpty() -> return emptyList()
            else -> networkRepository.listByIds(visibility.networkIds.toList())
        }
        val counts = networks.associate { it.id to serverRepository.countByNetworkId(it.id) }
        return networks.map { it.toResponse(counts[it.id] ?: 0) }
    }

    fun createNetwork(req: CreateNetworkRequest): NetworkResponse {
        if (networkRepository.findByName(req.name) != null) throw ConflictException("Network name already taken")
        val row = transaction {
            val e = Network.new {
                this.name = req.name
                this.proxyPort = req.proxyPort
                this.description = req.description
            }
            val r = ServerNetworks.selectAll().where { ServerNetworks.id eq e.id }.first()
            NetworkRow(
                id = r[ServerNetworks.id].value,
                name = r[ServerNetworks.name],
                proxyPort = r[ServerNetworks.proxyPort],
                description = r[ServerNetworks.description],
                createdAt = r[ServerNetworks.createdAt].toUtcString()
            )
        }
        createOverlayNetwork(row.id.toString())
        return row.toResponse(0)
    }

    fun getNetwork(id: Uuid): NetworkDetailResponse {
        val row = networkRepository.findById(id) ?: throw NotFoundException("Network not found")
        val members = serverRepository.listByNetworkId(id)
            .map { s ->
                NetworkServerItem(
                    id = s.id.toString(),
                    displayName = s.displayName,
                    serverType = s.serverType.toDb(),
                    status = ServerStatus.fromDb(s.status)
                )
            }
        return NetworkDetailResponse(
            id = row.id.toString(),
            name = row.name,
            proxyPort = row.proxyPort,
            description = row.description,
            serverCount = members.size,
            servers = members,
            createdAt = row.createdAt
        )
    }

    fun updateNetwork(id: Uuid, req: PatchNetworkRequest) {
        networkRepository.findById(id) ?: throw NotFoundException("Network not found")
        if (req.name != null) {
            val existing = networkRepository.findByName(req.name)
            if (existing != null && existing.id != id) throw ConflictException("Network name already taken")
        }
        transaction {
            Network.findById(id)?.let {
                if (req.name != null) it.name = req.name
                if (req.description != null) it.description = req.description
            }
        }
    }

    fun deleteNetwork(id: Uuid) {
        networkRepository.findById(id) ?: throw NotFoundException("Network not found")
        transaction { Servers.selectAll().where { Servers.networkId eq id }.forEach { Server.findById(it[Servers.id])?.let { s -> s.networkId = null } } }
        transaction { Network.findById(id)?.delete() }
        deleteOverlayNetwork(id.toString())
    }
}

private fun NetworkRow.toResponse(serverCount: Int) = NetworkResponse(
    id = id.toString(),
    name = name,
    proxyPort = proxyPort,
    description = description,
    serverCount = serverCount,
    createdAt = createdAt
)
