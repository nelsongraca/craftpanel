package io.craftpanel.master.service.repo

import io.craftpanel.master.database.schema.ProxyBackends
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class ProxyBackendRepositoryImpl : ProxyBackendRepository {

    override fun listProxyBackends(proxyServerId: Uuid): List<ProxyBackendRow> = transaction {
        ProxyBackends.selectAll()
            .where { ProxyBackends.proxyServerId eq proxyServerId }
            .orderBy(ProxyBackends.order, SortOrder.ASC)
            .map { it.toProxyBackendRow() }
    }

    override fun replaceProxyBackends(proxyServerId: Uuid, backends: List<ProxyBackendInput>) {
        transaction {
            ProxyBackends.deleteWhere { ProxyBackends.proxyServerId eq proxyServerId }
            backends.forEach { b ->
                ProxyBackends.insert {
                    it[ProxyBackends.proxyServerId] = proxyServerId
                    it[ProxyBackends.backendServerId] = b.backendServerId
                    it[ProxyBackends.backendName] = b.backendName
                    it[ProxyBackends.order] = b.order
                }
            }
        }
    }

    override fun findProxyServersForBackend(backendServerId: Uuid): List<Uuid> = transaction {
        ProxyBackends.selectAll()
            .where { ProxyBackends.backendServerId eq backendServerId }
            .map { it[ProxyBackends.proxyServerId] }
    }

    override fun deleteProxyBackendsForServer(serverId: Uuid) {
        transaction {
            ProxyBackends.deleteWhere { (ProxyBackends.proxyServerId eq serverId) or (ProxyBackends.backendServerId eq serverId) }
        }
    }
}

private fun ResultRow.toProxyBackendRow() = ProxyBackendRow(
    id = this[ProxyBackends.id],
    proxyServerId = this[ProxyBackends.proxyServerId],
    backendServerId = this[ProxyBackends.backendServerId],
    backendName = this[ProxyBackends.backendName],
    order = this[ProxyBackends.order]
)
