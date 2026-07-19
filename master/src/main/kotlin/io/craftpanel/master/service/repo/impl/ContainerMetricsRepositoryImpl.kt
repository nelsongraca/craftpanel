package io.craftpanel.master.service.repo.impl

import io.craftpanel.master.database.schema.ContainerMetrics
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
import io.craftpanel.master.util.toUtcString
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ContainerMetricsRepositoryImpl : ContainerMetricsRepository {

    override fun insertContainerMetrics(
        serverId: Uuid,
        cpuPercent: Double,
        ramUsedMb: Int,
        netInBytes: Long,
        netOutBytes: Long,
        blockInBytes: Long,
        blockOutBytes: Long,
        recordedAt: kotlin.time.Instant
    ) {
        transaction {
            ContainerMetrics.insert {
                it[ContainerMetrics.serverId] = serverId
                it[ContainerMetrics.cpuPercent] = cpuPercent
                it[ContainerMetrics.ramUsedMb] = ramUsedMb
                it[ContainerMetrics.netInBytes] = netInBytes
                it[ContainerMetrics.netOutBytes] = netOutBytes
                it[ContainerMetrics.blockInBytes] = blockInBytes
                it[ContainerMetrics.blockOutBytes] = blockOutBytes
                it[ContainerMetrics.recordedAt] = recordedAt.toLocalDateTime(TimeZone.UTC)
            }
        }
    }

    override fun getContainerMetrics(serverId: Uuid, seconds: Int): List<ContainerMetricsRow> = transaction {
        ContainerMetrics.selectAll()
            .where { (ContainerMetrics.serverId eq serverId) }
            .orderBy(ContainerMetrics.recordedAt to SortOrder.DESC)
            .map { it.toContainerMetricsRow() }
    }

    override fun getContainerMetricsByRange(serverId: Uuid, from: kotlin.time.Instant, to: Instant): List<ContainerMetricsRow> = transaction {
        val fromLdt = from.toLocalDateTime(TimeZone.UTC)
        val toLdt = to.toLocalDateTime(TimeZone.UTC)
        ContainerMetrics.selectAll()
            .where {
                (ContainerMetrics.serverId eq serverId) and
                    (ContainerMetrics.recordedAt greaterEq fromLdt) and
                    (ContainerMetrics.recordedAt lessEq toLdt)
            }
            .orderBy(ContainerMetrics.recordedAt to SortOrder.ASC)
            .map { it.toContainerMetricsRow() }
    }

    override fun getLatestContainerMetrics(serverId: Uuid): ContainerMetricsRow? = transaction {
        ContainerMetrics.selectAll()
            .where { ContainerMetrics.serverId eq serverId }
            .orderBy(ContainerMetrics.recordedAt to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.toContainerMetricsRow()
    }

    override fun getLatestContainerMetricsForServers(serverIds: List<Uuid>): Map<Uuid, ContainerMetricsRow?> = transaction {
        serverIds.associateWith { sid ->
            ContainerMetrics.selectAll()
                .where { ContainerMetrics.serverId eq sid }
                .orderBy(ContainerMetrics.recordedAt to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.toContainerMetricsRow()
        }
    }

    override fun deleteContainerMetricsForServer(serverId: Uuid) {
        transaction { ContainerMetrics.deleteWhere { ContainerMetrics.serverId eq serverId } }
    }
}

private fun ResultRow.toContainerMetricsRow() = ContainerMetricsRow(
    id = this[ContainerMetrics.id],
    serverId = this[ContainerMetrics.serverId],
    recordedAt = this[ContainerMetrics.recordedAt].toUtcString(),
    cpuPercent = this[ContainerMetrics.cpuPercent],
    ramUsedMb = this[ContainerMetrics.ramUsedMb],
    netInBytes = this[ContainerMetrics.netInBytes],
    netOutBytes = this[ContainerMetrics.netOutBytes],
    blockInBytes = this[ContainerMetrics.blockInBytes],
    blockOutBytes = this[ContainerMetrics.blockOutBytes]
)
