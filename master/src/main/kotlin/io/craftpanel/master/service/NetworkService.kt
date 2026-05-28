package io.craftpanel.master.service

import io.craftpanel.master.database.schema.ServerNetworks
import io.craftpanel.master.database.schema.Servers
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

@Serializable
data class NetworkResponse(
    val id: String,
    val name: String,
    val type: String,
    @SerialName("proxy_type") val proxyType: String?,
    @SerialName("proxy_port") val proxyPort: Int?,
    val description: String?,
    @SerialName("server_count") val serverCount: Int,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class NetworkServerItem(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("server_type") val serverType: String,
    val status: String,
)

@Serializable
data class NetworkDetailResponse(
    val id: String,
    val name: String,
    val type: String,
    @SerialName("proxy_type") val proxyType: String?,
    @SerialName("proxy_port") val proxyPort: Int?,
    val description: String?,
    @SerialName("server_count") val serverCount: Int,
    val servers: List<NetworkServerItem>,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class CreateNetworkRequest(
    val name: String,
    val type: String,
    @SerialName("proxy_type") val proxyType: String? = null,
    @SerialName("proxy_port") val proxyPort: Int? = null,
    val description: String? = null,
)

@Serializable
data class PatchNetworkRequest(
    val name: String? = null,
    val description: String? = null,
)

class NetworkService {

    fun listNetworks(): List<NetworkResponse> = transaction {
        val counts = Servers.selectAll()
            .groupBy({ it[Servers.networkId] }, { 1 })
            .mapValues { (_, v) -> v.size }
        ServerNetworks.selectAll().map { row ->
            val netId = row[ServerNetworks.id]
            NetworkResponse(
                id = netId.toString(),
                name = row[ServerNetworks.name],
                type = row[ServerNetworks.type],
                proxyType = row[ServerNetworks.proxyType],
                proxyPort = row[ServerNetworks.proxyPort],
                description = row[ServerNetworks.description],
                serverCount = counts[netId] ?: 0,
                createdAt = row[ServerNetworks.createdAt].toString(),
            )
        }
    }

    fun createNetwork(req: CreateNetworkRequest): NetworkResponse {
        val nameTaken = transaction {
            ServerNetworks.selectAll().where { ServerNetworks.name eq req.name }.firstOrNull() != null
        }
        if (nameTaken) throw ConflictException("Network name already taken")
        return transaction {
            val insertedId = ServerNetworks.insert {
                it[name] = req.name
                it[type] = req.type
                it[proxyType] = req.proxyType
                it[proxyPort] = req.proxyPort
                it[description] = req.description
            }[ServerNetworks.id]
            val row = ServerNetworks.selectAll().where { ServerNetworks.id eq insertedId }.first()
            NetworkResponse(
                id = insertedId.toString(),
                name = row[ServerNetworks.name],
                type = row[ServerNetworks.type],
                proxyType = row[ServerNetworks.proxyType],
                proxyPort = row[ServerNetworks.proxyPort],
                description = row[ServerNetworks.description],
                serverCount = 0,
                createdAt = row[ServerNetworks.createdAt].toString(),
            )
        }
    }

    fun getNetwork(id: kotlin.uuid.Uuid): NetworkDetailResponse =
        transaction {
            val row = ServerNetworks.selectAll().where { ServerNetworks.id eq id }.firstOrNull()
                ?: return@transaction null
            val members = Servers.selectAll().where { Servers.networkId eq id }.map { s ->
                NetworkServerItem(
                    id = s[Servers.id].toString(),
                    displayName = s[Servers.displayName],
                    serverType = s[Servers.serverType],
                    status = s[Servers.status],
                )
            }
            NetworkDetailResponse(
                id = row[ServerNetworks.id].toString(),
                name = row[ServerNetworks.name],
                type = row[ServerNetworks.type],
                proxyType = row[ServerNetworks.proxyType],
                proxyPort = row[ServerNetworks.proxyPort],
                description = row[ServerNetworks.description],
                serverCount = members.size,
                servers = members,
                createdAt = row[ServerNetworks.createdAt].toString(),
            )
        } ?: throw NotFoundException("Network not found")

    fun updateNetwork(id: kotlin.uuid.Uuid, req: PatchNetworkRequest) {
        val result: Boolean? = transaction {
            val exists = ServerNetworks.selectAll().where { ServerNetworks.id eq id }.firstOrNull() != null
            if (!exists) return@transaction false
            if (req.name != null) {
                val nameTaken = ServerNetworks.selectAll()
                    .where { (ServerNetworks.name eq req.name) and (ServerNetworks.id neq id) }
                    .firstOrNull() != null
                if (nameTaken) return@transaction null
            }
            ServerNetworks.update({ ServerNetworks.id eq id }) {
                if (req.name != null) it[name] = req.name
                if (req.description != null) it[description] = req.description
            }
            true
        }
        when (result) {
            null -> throw ConflictException("Network name already taken")
            false -> throw NotFoundException("Network not found")
            else -> Unit
        }
    }

    fun deleteNetwork(id: kotlin.uuid.Uuid) {
        val deleted = transaction {
            val exists = ServerNetworks.selectAll().where { ServerNetworks.id eq id }.firstOrNull() != null
            if (!exists) return@transaction false
            Servers.update({ Servers.networkId eq id }) { it[networkId] = null }
            ServerNetworks.deleteWhere { ServerNetworks.id eq id }
            true
        }
        if (!deleted) throw NotFoundException("Network not found")
    }
}
