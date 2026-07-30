package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.ReferenceOption

object ProxyBackends : UuidTable("proxy_backends") {

    val proxyServerId = reference("proxy_server_id", Servers, onDelete = ReferenceOption.CASCADE)
    val backendServerId = reference("backend_server_id", Servers, onDelete = ReferenceOption.CASCADE)
    val backendName = varchar("backend_name", 64)
    val order = integer("order").default(0)

    init {
        uniqueIndex(proxyServerId, backendName)
    }
}
