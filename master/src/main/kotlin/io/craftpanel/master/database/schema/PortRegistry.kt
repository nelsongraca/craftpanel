package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.ReferenceOption

object PortRegistry : Table("port_registry") {

    val nodeId = reference("node_id", Nodes, onDelete = ReferenceOption.CASCADE)
    val port = integer("port")
    val protocol = varchar("protocol", 3) // TCP | UDP
    val serverId = reference("server_id", Servers, onDelete = ReferenceOption.CASCADE).nullable()

    override val primaryKey = PrimaryKey(nodeId, port, protocol)
}
