package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

object NodeMetrics : Table("node_metrics") {

    val id = uuid("id").autoGenerate()
    val nodeId = uuid("node_id").references(Nodes.id)
    val recordedAt = datetime("recorded_at")

    val cpuPercent = double("cpu_percent")
    val ramUsedMb = integer("ram_used_mb")
    val ramTotalMb = integer("ram_total_mb")
    val netInBytes = long("net_in_bytes")
    val netOutBytes = long("net_out_bytes")
    val diskUsedBytes = long("disk_used_bytes")
    val diskTotalBytes = long("disk_total_bytes")

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, nodeId, recordedAt)
    }
}
