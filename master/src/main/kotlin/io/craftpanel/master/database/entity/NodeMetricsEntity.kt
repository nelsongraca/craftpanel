package io.craftpanel.master.database.entity

import io.craftpanel.master.database.schema.NodeMetrics
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import kotlin.uuid.Uuid

class NodeMetricsEntity(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<NodeMetricsEntity>(NodeMetrics)

    var nodeId by NodeMetrics.nodeId
    var recordedAt by NodeMetrics.recordedAt
    var cpuPercent by NodeMetrics.cpuPercent
    var ramUsedMb by NodeMetrics.ramUsedMb
    var ramTotalMb by NodeMetrics.ramTotalMb
    var netInBytes by NodeMetrics.netInBytes
    var netOutBytes by NodeMetrics.netOutBytes
    var diskUsedBytes by NodeMetrics.diskUsedBytes
    var diskTotalBytes by NodeMetrics.diskTotalBytes
}
