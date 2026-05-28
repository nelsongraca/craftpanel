package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

object ServerNetworks : Table("server_networks") {

    val id = uuid("id").autoGenerate()
    val name = varchar("name", 100).uniqueIndex()
    val type = varchar("type", 10)              // PROXY | VANILLA
    val proxyType = varchar("proxy_type", 12).nullable()   // VELOCITY | BUNGEECORD
    val proxyPort = integer("proxy_port").nullable()
    val description = varchar("description", 500).nullable()
    val cfZoneId = varchar("cf_zone_id", 100).nullable()
    val cfDomainSuffix = varchar("cf_domain_suffix", 255).nullable()
    val dnsProviderType = varchar("dns_provider_type", 20).nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}
