package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

class FakeContainerMetricsRepository(private val state: FakeRepositories) : ContainerMetricsRepository {

    override fun getContainerMetrics(serverId: Uuid, seconds: Int): List<ContainerMetricsRow> = state.containerMetrics.filter { it.serverId == serverId }
        .map { toRow(it) }

    override fun getContainerMetricsByRange(serverId: Uuid, from: kotlin.time.Instant, to: kotlin.time.Instant): List<ContainerMetricsRow> = state.containerMetrics.filter { it.serverId == serverId }
        .map { toRow(it) }

    override fun getLatestContainerMetrics(serverId: Uuid): ContainerMetricsRow? = state.containerMetrics.filter { it.serverId == serverId }
        .maxByOrNull { it.recordedAt }
        ?.let { toRow(it) }

    override fun getLatestContainerMetricsForServers(serverIds: List<Uuid>): Map<Uuid, ContainerMetricsRow?> = serverIds.associateWith { getLatestContainerMetrics(it) }

    private fun toRow(m: FakeServerRepository.MutableContainerMetrics) =
        ContainerMetricsRow(Uuid.random(), m.serverId, m.recordedAt, m.cpuPercent, m.ramUsedMb, m.netInBytes, m.netOutBytes, m.blockInBytes, m.blockOutBytes)
}