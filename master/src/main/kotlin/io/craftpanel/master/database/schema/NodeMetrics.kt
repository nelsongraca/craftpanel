package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.datetime.datetime

object NodeMetrics : UuidTable("node_metrics") {

    val nodeId = reference("node_id", Nodes, onDelete = ReferenceOption.CASCADE)
    val recordedAt = datetime("recorded_at")

    val cpuPercent = double("cpu_percent")
    val ramUsedMb = integer("ram_used_mb")
    val ramTotalMb = integer("ram_total_mb")
    val netInBytes = long("net_in_bytes")
    val netOutBytes = long("net_out_bytes")
    val diskUsedBytes = long("disk_used_bytes")
    val diskTotalBytes = long("disk_total_bytes")

    init {
        index(false, nodeId, recordedAt)
    }
}
