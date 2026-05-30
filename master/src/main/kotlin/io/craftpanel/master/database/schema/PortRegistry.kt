package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object PortRegistry : Table("port_registry") {

    val nodeId = uuid("node_id").references(Nodes.id)
    val port = integer("port")
    val protocol = varchar("protocol", 3)           // TCP | UDP
    val serverId = uuid("server_id")
        .references(Servers.id, onDelete = ReferenceOption.SET_NULL)
        .nullable()

    override val primaryKey = PrimaryKey(nodeId, port, protocol)
}
