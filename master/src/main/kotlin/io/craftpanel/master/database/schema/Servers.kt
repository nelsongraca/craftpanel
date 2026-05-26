package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

object Servers : Table("servers") {
    val id = uuid("id").autoGenerate()
    val name = varchar("name", 100).uniqueIndex()
    val displayName = varchar("display_name", 100).default("")
    val description = varchar("description", 500).nullable()
    val nodeId = uuid("node_id").references(Nodes.id)
    val networkId = uuid("network_id").references(ServerNetworks.id).nullable()
    val serverType = varchar("server_type", 20).default("VANILLA")
    val status = varchar("status", 10).default("STOPPED")  // STOPPED|STARTING|HEALTHY|STOPPING|UNHEALTHY
    val hostPort = integer("host_port")
    val memoryMb = integer("memory_mb")
    val cpuShares = integer("cpu_shares").default(0)        // 0 = unlimited
    val exposedExternally = bool("exposed_externally").default(false)
    val publicSubdomain = varchar("public_subdomain", 253).nullable().uniqueIndex()
    val dnsRecordId = varchar("dns_record_id", 100).nullable()
    val dnsRecordName = varchar("dns_record_name", 255).nullable()
    val configMode = varchar("config_mode", 10).default("MANAGED")  // MANAGED | MANUAL
    val stopCommand = varchar("stop_command", 50).default("stop")
    val itzgImageTag = varchar("itzg_image_tag", 100).default("latest")
    val containerId = varchar("container_id", 64).nullable()
    val lastSeenAt = datetime("last_seen_at").nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}
