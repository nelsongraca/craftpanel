package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

object PortRegistry : Table("port_registry") {

    val nodeId = uuid("node_id").references(Nodes.id)
    val port = integer("port")
    val protocol = varchar("protocol", 3)           // TCP | UDP
    val serverId = uuid("server_id").references(Servers.id)
        .nullable()
    val allocatedAt = datetime("allocated_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(nodeId, port, protocol)
}
