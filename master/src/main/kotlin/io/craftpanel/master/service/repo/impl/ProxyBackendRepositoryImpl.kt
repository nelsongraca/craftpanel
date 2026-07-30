package io.craftpanel.master.service.repo.impl

import io.craftpanel.master.database.schema.ProxyBackends
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
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

    override fun findProxyServersForBackend(backendServerId: Uuid): List<Uuid> = transaction {
        ProxyBackends.selectAll()
            .where { ProxyBackends.backendServerId eq backendServerId }
            .map { it[ProxyBackends.proxyServerId].value }
    }
}

private fun ResultRow.toProxyBackendRow() = ProxyBackendRow(
    id = this[ProxyBackends.id].value,
    proxyServerId = this[ProxyBackends.proxyServerId].value,
    backendServerId = this[ProxyBackends.backendServerId].value,
    backendName = this[ProxyBackends.backendName],
    order = this[ProxyBackends.order]
)