package io.craftpanel.master.service.repo

import io.craftpanel.master.database.schema.ServerNetworks
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
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

    override fun create(
        name: String,
        proxyPort: Int?,
        description: String?,
        cfDomainSuffix: String?,
        cfZoneId: String?,
        dnsProviderType: String?,
    ): NetworkRow = transaction {
        val id = ServerNetworks.insert {
            it[ServerNetworks.name] = name
            it[ServerNetworks.proxyPort] = proxyPort
            it[ServerNetworks.description] = description
            it[ServerNetworks.cfDomainSuffix] = cfDomainSuffix
            it[ServerNetworks.cfZoneId] = cfZoneId
            it[ServerNetworks.dnsProviderType] = dnsProviderType
        }[ServerNetworks.id]
        ServerNetworks.selectAll()
            .where { ServerNetworks.id eq id }
            .first()
            .toNetworkRow()
    }

    override fun update(
        id: Uuid,
        name: String?,
        description: String?,
        cfDomainSuffix: String?,
        cfZoneId: String?,
        dnsProviderType: String?,
    ) {
        transaction {
            ServerNetworks.update({ ServerNetworks.id eq id }) {
                if (name != null) it[ServerNetworks.name] = name
                if (description != null) it[ServerNetworks.description] = description
                if (cfDomainSuffix != null) it[ServerNetworks.cfDomainSuffix] = cfDomainSuffix
                if (cfZoneId != null) it[ServerNetworks.cfZoneId] = cfZoneId
                if (dnsProviderType != null) it[ServerNetworks.dnsProviderType] = dnsProviderType
            }
        }
    }

    override fun delete(id: Uuid) {
        transaction {
            ServerNetworks.deleteWhere { ServerNetworks.id eq id }
        }
    }
}

private fun org.jetbrains.exposed.v1.core.ResultRow.toNetworkRow() = NetworkRow(
    id = this[ServerNetworks.id],
    name = this[ServerNetworks.name],
    proxyPort = this[ServerNetworks.proxyPort],
    description = this[ServerNetworks.description],
    cfZoneId = this[ServerNetworks.cfZoneId],
    cfDomainSuffix = this[ServerNetworks.cfDomainSuffix],
    dnsProviderType = this[ServerNetworks.dnsProviderType],
    createdAt = this[ServerNetworks.createdAt].toUtcString(),
)
