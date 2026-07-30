package io.craftpanel.master.database.entity

import io.craftpanel.master.database.schema.ContainerMetrics
import io.craftpanel.master.service.repo.ContainerMetricsRow
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import kotlin.uuid.Uuid

class ContainerMetricsEntity(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<ContainerMetricsEntity>(ContainerMetrics)

    var serverId by ContainerMetrics.serverId
    var recordedAt by ContainerMetrics.recordedAt
    var cpuPercent by ContainerMetrics.cpuPercent
    var ramUsedMb by ContainerMetrics.ramUsedMb
    var netInBytes by ContainerMetrics.netInBytes
    var netOutBytes by ContainerMetrics.netOutBytes
    var blockInBytes by ContainerMetrics.blockInBytes
    var blockOutBytes by ContainerMetrics.blockOutBytes

    fun toContainerMetricsRow() = ContainerMetricsRow(
        id = id.value,
        serverId = serverId.value,
        recordedAt = recordedAt.toUtcString(),
        cpuPercent = cpuPercent,
        ramUsedMb = ramUsedMb,
        netInBytes = netInBytes,
        netOutBytes = netOutBytes,
        blockInBytes = blockInBytes,
        blockOutBytes = blockOutBytes
    )
}
