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
    val networkId = uuid("network_id").references(ServerNetworks.id)
        .nullable()
    val serverType = varchar("server_type", 20).default("VANILLA")
    val mcVersion = varchar("mc_version", 16).default("LATEST")
    val status = varchar("status", 10).default("STOPPED") // STOPPED|STARTING|HEALTHY|STOPPING|UNHEALTHY
    val hostPort = integer("host_port")
    val memoryMb = integer("memory_mb")
    val cpuShares = integer("cpu_shares").default(0) // 0 = unlimited
    val exposedExternally = bool("exposed_externally").default(false)
    val publicSubdomain = varchar("public_subdomain", 253).nullable()
        .uniqueIndex()
    val dnsRecordId = varchar("dns_record_id", 100).nullable()
    val dnsRecordName = varchar("dns_record_name", 255).nullable()
    val customHostname = varchar("custom_hostname", 253).nullable()
        .uniqueIndex()
    val configMode = varchar("config_mode", 10).default("MANAGED") // MANAGED | MANUAL
    val stopCommand = varchar("stop_command", 64).default("stop")
    val itzgImageTag = varchar("itzg_image_tag", 100).default("latest")
    val needsRecreate = bool("needs_recreate").default(false)
    val proxyMotd = varchar("proxy_motd", 500).nullable()
    val proxyMaxPlayers = integer("proxy_max_players").nullable()
    val proxyForwardingMode = varchar("proxy_forwarding_mode", 20).nullable()
    val backupSchedule = varchar("backup_schedule", 64).nullable()
    val backupMaxCount = integer("backup_max_count").default(10)
    val backupScheduleLastFired = datetime("backup_schedule_last_fired").nullable()
    val lastPlayerCount = integer("last_player_count").nullable()
    val lastPlayerNames = varchar("last_player_names", 1000).nullable()
    val lastPlayerUpdate = datetime("last_player_update").nullable()
    val lastSeenAt = datetime("last_seen_at").nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}
