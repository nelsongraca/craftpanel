package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.Table

object ProxyBackends : Table("proxy_backends") {

    val id = uuid("id").autoGenerate()
    val networkId = uuid("network_id").references(ServerNetworks.id)
    val serverId = uuid("server_id").references(Servers.id)

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(networkId, serverId)
    }
}
