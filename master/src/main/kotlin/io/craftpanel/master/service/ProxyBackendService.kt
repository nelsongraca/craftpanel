package io.craftpanel.master.service

import io.craftpanel.master.database.schema.ProxyBackends
import io.craftpanel.master.database.schema.Servers
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.uuid.Uuid

@Serializable
data class ProxyBackendItem(
    val id: String,
    @SerialName("backend_server_id") val backendServerId: String,
    @SerialName("backend_name") val backendName: String,
    val order: Int,
)

@Serializable
data class ProxyBackendListResponse(val backends: List<ProxyBackendItem>)

@Serializable
data class BackendInput(
    @SerialName("backend_server_id") val backendServerId: String,
    @SerialName("backend_name") val backendName: String,
    val order: Int,
)

@Serializable
data class PutProxyBackendsRequest(val backends: List<BackendInput>)

class ProxyBackendService {

    data class ServerScope(val serverIdJava: UUID, val networkId: UUID?)

    fun getServerScope(serverId: Uuid): ServerScope? =
        transaction {
            Servers.selectAll()
                .where { Servers.id eq serverId }
                .firstOrNull()
                ?.let {
                    ServerScope(
                        serverIdJava = UUID.fromString(serverId.toString()),
                        networkId = it[Servers.networkId]?.let { nid -> UUID.fromString(nid.toString()) },
                    )
                }
        }

    fun listBackends(proxyServerId: Uuid): ProxyBackendListResponse {
        val serverRow = transaction {
            Servers.selectAll().where { Servers.id eq proxyServerId }.firstOrNull()
        } ?: throw NotFoundException("Server not found")

        if (serverRow[Servers.serverType] !in PROXY_SERVER_TYPES)
            throw ConflictException("Server is not a proxy type")

        val items = transaction {
            ProxyBackends.selectAll()
                .where { ProxyBackends.proxyServerId eq proxyServerId }
                .orderBy(ProxyBackends.order, SortOrder.ASC)
                .map {
                    ProxyBackendItem(
                        id = it[ProxyBackends.id].toString(),
                        backendServerId = it[ProxyBackends.backendServerId].toString(),
                        backendName = it[ProxyBackends.backendName],
                        order = it[ProxyBackends.order],
                    )
                }
        }
        return ProxyBackendListResponse(items)
    }

    fun replaceBackends(proxyServerId: Uuid, req: PutProxyBackendsRequest): ProxyBackendListResponse {
        val serverRow = transaction {
            Servers.selectAll().where { Servers.id eq proxyServerId }.firstOrNull()
        } ?: throw NotFoundException("Server not found")

        if (serverRow[Servers.serverType] !in PROXY_SERVER_TYPES)
            throw ConflictException("Server is not a proxy type")

        val names = req.backends.map { it.backendName.trim() }
        if (names.size != names.toSet().size)
            throw UnprocessableException("Duplicate backend names")

        transaction {
            for (b in req.backends) {
                val backendId = runCatching { Uuid.parse(b.backendServerId) }.getOrNull()
                    ?: throw UnprocessableException("Invalid backend_server_id: ${b.backendServerId}")

                val backendRow = Servers.selectAll().where { Servers.id eq backendId }.firstOrNull()
                    ?: throw UnprocessableException("Backend server not found: ${b.backendServerId}")

                if (backendRow[Servers.serverType] in PROXY_SERVER_TYPES)
                    throw UnprocessableException("Backend server cannot be a proxy type: ${b.backendServerId}")
            }

            ProxyBackends.deleteWhere { ProxyBackends.proxyServerId eq proxyServerId }

            for (b in req.backends) {
                val backendId = Uuid.parse(b.backendServerId)
                ProxyBackends.insert {
                    it[ProxyBackends.proxyServerId] = proxyServerId
                    it[ProxyBackends.backendServerId] = backendId
                    it[ProxyBackends.backendName] = b.backendName.trim()
                    it[ProxyBackends.order] = b.order
                }
            }
        }

        return listBackends(proxyServerId)
    }
}
