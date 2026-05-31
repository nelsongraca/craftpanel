package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object ProxyBackends : Table("proxy_backends") {

    val id = uuid("id").autoGenerate()
    val proxyServerId = uuid("proxy_server_id").references(Servers.id, onDelete = ReferenceOption.CASCADE)
    val backendServerId = uuid("backend_server_id").references(Servers.id, onDelete = ReferenceOption.CASCADE)
    val backendName = varchar("backend_name", 64)
    val order = integer("order").default(0)

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(proxyServerId, backendName)
    }
}
