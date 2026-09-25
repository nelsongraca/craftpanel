package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.datetime

object ContainerMetrics : UuidTable("container_metrics") {

    val serverId = reference("server_id", Servers, onDelete = ReferenceOption.CASCADE)
    val recordedAt = datetime("recorded_at")
    val cpuPercent = double("cpu_percent")
    val ramUsedMb = integer("ram_used_mb")
    val netInBytes = long("net_in_bytes")
    val netOutBytes = long("net_out_bytes")
    val blockInBytes = long("block_in_bytes")
    val blockOutBytes = long("block_out_bytes")

    // JVM heap sample. Null = no sample this tick (JVM metrics disabled, non-JVM server, or not
    // attachable) — never zeroed, so history shows gaps rather than fake dips.
    val heapUsedBytes = long("heap_used_bytes").nullable()
    val heapMaxBytes = long("heap_max_bytes").nullable()
    val nonHeapUsedBytes = long("non_heap_used_bytes").nullable()

    init {
        index(false, serverId, recordedAt)
    }
}
