package io.craftpanel.master.database.entity

import io.craftpanel.master.database.schema.ProxyBackends
import io.craftpanel.master.service.repo.ProxyBackendRow
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import kotlin.uuid.Uuid

class ProxyBackend(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<ProxyBackend>(ProxyBackends)

    var proxyServerId by ProxyBackends.proxyServerId
    var backendServerId by ProxyBackends.backendServerId
    var backendName by ProxyBackends.backendName
    var order by ProxyBackends.order

    fun toProxyBackendRow() = ProxyBackendRow(
        id = id.value,
        proxyServerId = proxyServerId.value,
        backendServerId = backendServerId.value,
        backendName = backendName,
        order = order
    )
}
