package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

object ServerNetworks : UuidTable("server_networks") {

    val name = varchar("name", 100).uniqueIndex()
    val proxyPort = integer("proxy_port").nullable()
    val description = varchar("description", 500).nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
}
