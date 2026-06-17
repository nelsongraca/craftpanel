package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

object Nodes : Table("nodes") {

    val id = uuid("id").autoGenerate()
    val displayName = varchar("display_name", 100)
    val hostname = varchar("hostname", 255)
    val publicIp = varchar("public_ip", 45)
    val privateIp = varchar("private_ip", 45)

    // SHA-256 hex of the 256-bit node key
    val tokenHash = varchar("token_hash", 64).uniqueIndex()

    // PENDING | ACTIVE | REJECTED | DECOMMISSIONED
    val status = varchar("status", 20).default("PENDING")

    // HEALTHY | DEGRADED | UNREACHABLE — only meaningful when status == ACTIVE
    val health = varchar("health", 20).default("HEALTHY")

    val totalRamMb = integer("total_ram_mb").default(0)
    val totalCpuShares = integer("total_cpu_shares").default(0)
    val systemRamUsedMb = integer("system_ram_used_mb").nullable()

    val portRangeStart = integer("port_range_start").default(25570)
    val portRangeEnd = integer("port_range_end").default(26070)
    val agentVersion = varchar("agent_version", 50).nullable()

    val lastSeenAt = datetime("last_seen_at").nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}
