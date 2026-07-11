package io.craftpanel.master.service.repo

import kotlinx.datetime.Instant
import kotlin.uuid.Uuid

class FakeContainerMetricsRepository(private val state: FakeRepositories) : ContainerMetricsRepository {

    override fun insertContainerMetrics(serverId: Uuid, cpuPercent: Double, ramUsedMb: Int, netInBytes: Long, netOutBytes: Long, blockInBytes: Long, blockOutBytes: Long, recordedAt: Instant) {
        state.containerMetrics.add(FakeServerRepository.MutableContainerMetrics(serverId, recordedAt.toString(), cpuPercent, ramUsedMb, netInBytes, netOutBytes, blockInBytes, blockOutBytes))
    }

    override fun getContainerMetrics(serverId: Uuid, seconds: Int): List<ContainerMetricsRow> = state.containerMetrics.filter { it.serverId == serverId }
        .map { toRow(it) }

    override fun getContainerMetricsByRange(serverId: Uuid, from: Instant, to: Instant): List<ContainerMetricsRow> = state.containerMetrics.filter { it.serverId == serverId }
        .map { toRow(it) }

    override fun getLatestContainerMetrics(serverId: Uuid): ContainerMetricsRow? = state.containerMetrics.filter { it.serverId == serverId }
        .maxByOrNull { it.recordedAt }
        ?.let { toRow(it) }

    override fun getLatestContainerMetricsForServers(serverIds: List<Uuid>): Map<Uuid, ContainerMetricsRow?> = serverIds.associateWith { getLatestContainerMetrics(it) }

    override fun deleteContainerMetricsForServer(serverId: Uuid) {
        state.containerMetrics.removeAll { it.serverId == serverId }
    }

    private fun toRow(m: FakeServerRepository.MutableContainerMetrics) =
        ContainerMetricsRow(Uuid.random(), m.serverId, m.recordedAt, m.cpuPercent, m.ramUsedMb, m.netInBytes, m.netOutBytes, m.blockInBytes, m.blockOutBytes)
}
