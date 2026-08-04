package io.craftpanel.master.database.entity

import io.craftpanel.master.database.schema.ServerNetworks
import io.craftpanel.master.service.repo.NetworkRow
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import kotlin.uuid.Uuid

class Network(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<Network>(ServerNetworks)

    var name by ServerNetworks.name
    var proxyPort by ServerNetworks.proxyPort
    var description by ServerNetworks.description
    var createdAt by ServerNetworks.createdAt

    fun toNetworkRow() = NetworkRow(
        id = id.value,
        name = name,
        proxyPort = proxyPort,
        description = description,
        createdAt = createdAt.toUtcString()
    )
}
