package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

class FakeNetworkRepository : NetworkRepository {

    private val networks = mutableMapOf<Uuid, MutableNetwork>()

    data class MutableNetwork(var id: Uuid, var name: String, var proxyPort: Int?, var description: String?, var createdAt: String = "2025-01-01T00:00:00Z")

    override fun findById(id: Uuid): NetworkRow? = networks[id]?.toRow()
    override fun findByName(name: String): NetworkRow? = networks.values.firstOrNull { it.name == name }
        ?.toRow()

    override fun listAll(): List<NetworkRow> = networks.values.map { it.toRow() }
    override fun listByIds(ids: List<Uuid>): List<NetworkRow> = ids.mapNotNull { networks[it]?.toRow() }

    fun addNetwork(name: String, proxyPort: Int?, description: String?): NetworkRow {
        val id = Uuid.random()
        val row = MutableNetwork(id, name, proxyPort, description)
        networks[id] = row
        return row.toRow()
    }

    private fun MutableNetwork.toRow() = NetworkRow(id, name, proxyPort, description, createdAt)
}
