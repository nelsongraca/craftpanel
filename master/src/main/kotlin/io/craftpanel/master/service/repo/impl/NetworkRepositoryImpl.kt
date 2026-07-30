package io.craftpanel.master.service.repo.impl

import io.craftpanel.master.database.schema.ServerNetworks
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class NetworkRepositoryImpl : NetworkRepository {

    override fun findById(id: Uuid): NetworkRow? = transaction {
        ServerNetworks.selectAll()
            .where { ServerNetworks.id eq id }
            .firstOrNull()
            ?.toNetworkRow()
    }

    override fun findByName(name: String): NetworkRow? = transaction {
        ServerNetworks.selectAll()
            .where { ServerNetworks.name eq name }
            .firstOrNull()
            ?.toNetworkRow()
    }

    override fun listAll(): List<NetworkRow> = transaction {
        ServerNetworks.selectAll()
            .map { it.toNetworkRow() }
    }

    override fun listByIds(ids: List<Uuid>): List<NetworkRow> = transaction {
        ServerNetworks.selectAll()
            .where { ServerNetworks.id inList ids }
            .map { it.toNetworkRow() }
    }
}

private fun org.jetbrains.exposed.v1.core.ResultRow.toNetworkRow() = NetworkRow(
    id = this[ServerNetworks.id].value,
    name = this[ServerNetworks.name],
    proxyPort = this[ServerNetworks.proxyPort],
    description = this[ServerNetworks.description],
    cfZoneId = this[ServerNetworks.cfZoneId],
    cfDomainSuffix = this[ServerNetworks.cfDomainSuffix],
    dnsProviderType = this[ServerNetworks.dnsProviderType],
    createdAt = this[ServerNetworks.createdAt].toUtcString()
)