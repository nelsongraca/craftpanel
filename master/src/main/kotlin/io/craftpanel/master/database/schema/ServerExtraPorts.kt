package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

object ServerExtraPorts : UuidTable("server_extra_ports") {
    val serverId = reference("server_id", Servers, onDelete = ReferenceOption.CASCADE)
    val nodeId = reference("node_id", Nodes, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 64)
    val containerPort = integer("container_port")
    val hostPort = integer("host_port")
    val protocol = varchar("protocol", 4).default("TCP") // TCP | UDP
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
}
