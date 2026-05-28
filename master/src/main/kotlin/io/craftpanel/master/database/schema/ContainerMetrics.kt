package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

object ContainerMetrics : Table("container_metrics") {

    val id = uuid("id").autoGenerate()
    val serverId = uuid("server_id").references(Servers.id)
    val recordedAt = datetime("recorded_at")
    val cpuPercent = double("cpu_percent")
    val ramUsedMb = integer("ram_used_mb")
    val netInBytes = long("net_in_bytes")
    val netOutBytes = long("net_out_bytes")

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, serverId, recordedAt)
    }
}
