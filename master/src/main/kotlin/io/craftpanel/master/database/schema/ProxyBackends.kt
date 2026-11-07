package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable

object ProxyBackends : UuidTable("proxy_backends") {

    val proxyServerId = reference("proxy_server_id", Servers, onDelete = ReferenceOption.CASCADE)
    val backendServerId = reference("backend_server_id", Servers, onDelete = ReferenceOption.CASCADE)
    val backendName = varchar("backend_name", 64)
    val order = integer("order").default(0)

    /**
     * Whether this backend belongs to the proxy's join order (Velocity `servers.try`,
     * BungeeCord/Waterfall `listeners[0].priorities`). Only the selected backends are
     * written there; every backend still gets an entry in the proxy's server registry.
     */
    val inJoinList = bool("in_join_list").default(true)

    init {
        uniqueIndex(proxyServerId, backendName)
    }
}
