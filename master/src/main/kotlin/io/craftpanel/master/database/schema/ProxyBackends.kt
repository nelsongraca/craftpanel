package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.Table

object ProxyBackends : Table("proxy_backends") {

    val id = uuid("id").autoGenerate()
    val proxyServerId = uuid("proxy_server_id").references(Servers.id)
    val backendServerId = uuid("backend_server_id").references(Servers.id)
    val backendName = varchar("backend_name", 64)
    val order = integer("order").default(0)

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(proxyServerId, backendName)
    }
}
