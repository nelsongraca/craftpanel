package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

class FakeNetworkRepository : NetworkRepository {

    private val networks = mutableMapOf<Uuid, MutableNetwork>()
    private val nextIndex = mutableMapOf<String, Int>()

    data class MutableNetwork(
        var id: Uuid,
        var name: String,
        var proxyPort: Int?,
        var description: String?,
        var cfZoneId: String?,
        var cfDomainSuffix: String?,
        var dnsProviderType: String?,
        var createdAt: String = "2025-01-01T00:00:00Z",
    )

    override fun findById(id: Uuid): NetworkRow? = networks[id]?.toRow()
    override fun findByName(name: String): NetworkRow? = networks.values.firstOrNull { it.name == name }
        ?.toRow()

    override fun listAll(): List<NetworkRow> = networks.values.map { it.toRow() }
    override fun listByIds(ids: List<Uuid>): List<NetworkRow> = ids.mapNotNull { networks[it]?.toRow() }

    override fun create(
        name: String,
        proxyPort: Int?,
        description: String?,
        cfDomainSuffix: String?,
        cfZoneId: String?,
        dnsProviderType: String?,
    ): NetworkRow {
        val id = Uuid.random()
        val row = MutableNetwork(id, name, proxyPort, description, cfZoneId, cfDomainSuffix, dnsProviderType)
        networks[id] = row
        return row.toRow()
    }

    override fun update(id: Uuid, name: String?, description: String?, cfDomainSuffix: String?, cfZoneId: String?, dnsProviderType: String?) {
        val n = networks[id] ?: return
        if (name != null) n.name = name
        if (description != null) n.description = description
        if (cfDomainSuffix != null) n.cfDomainSuffix = cfDomainSuffix
        if (cfZoneId != null) n.cfZoneId = cfZoneId
        if (dnsProviderType != null) n.dnsProviderType = dnsProviderType
    }

    override fun delete(id: Uuid) {
        networks.remove(id)
    }

    private fun MutableNetwork.toRow() = NetworkRow(id, name, proxyPort, description, cfZoneId, cfDomainSuffix, dnsProviderType, createdAt)
}
