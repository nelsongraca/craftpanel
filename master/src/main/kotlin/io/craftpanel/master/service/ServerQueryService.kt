package io.craftpanel.master.service

import io.craftpanel.master.service.repo.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class ContainerMetricsPoint(val t: String, val v: Double)

@Serializable
data class ContainerMetricsPointLong(val t: String, val v: Long)

@Serializable
data class ContainerMetricsSeriesResponse(@SerialName("server_id") val serverId: String, val series: ContainerMetricsSeries)

@Serializable
data class ContainerMetricsSeries(
    @SerialName("cpu_percent") val cpuPercent: List<ContainerMetricsPoint>,
    @SerialName("ram_used_mb") val ramUsedMb: List<ContainerMetricsPoint>,
    @SerialName("net_in_bytes") val netInBytes: List<ContainerMetricsPointLong>,
    @SerialName("net_out_bytes") val netOutBytes: List<ContainerMetricsPointLong>
)

class ServerQueryService(
    private val serverRepository: ServerRepository,
    private val userRepository: UserRepository,
    private val groupRepository: GroupRepository,
    private val containerMetricsRepository: ContainerMetricsRepository,
    private val migrationRepository: MigrationRepository
) {

    private val visibilityResolver = ServerVisibilityResolver(userRepository, groupRepository)

    fun isMigrating(id: Uuid): Boolean = migrationRepository.findActiveMigration(id) != null

    fun listServers(userId: Uuid): List<ServerRow> {
        val visibility = visibilityResolver.resolve(userId)
        val rows = when {
            visibility.isGlobal -> serverRepository.listAll()

            visibility.networkIds.isEmpty() && visibility.serverIds.isEmpty() -> return emptyList()

            else -> serverRepository.listByVisibility(
                visibility.networkIds.toList(),
                visibility.serverIds.toList()
            )
        }
        return rows
    }

    fun getServer(id: Uuid): ServerRow = serverRepository.findById(id) ?: throw NotFoundException("Server not found")

    fun getMetrics(id: Uuid, from: Instant, to: Instant): ContainerMetricsSeriesResponse {
        serverRepository.findById(id) ?: throw NotFoundException("Server not found")
        val rows = containerMetricsRepository.getContainerMetricsByRange(id, from, to)
        return ContainerMetricsSeriesResponse(
            serverId = id.toString(),
            series = ContainerMetricsSeries(
                cpuPercent = rows.map { ContainerMetricsPoint(it.recordedAt, it.cpuPercent) },
                ramUsedMb = rows.map { ContainerMetricsPoint(it.recordedAt, it.ramUsedMb.toDouble()) },
                netInBytes = rows.map { ContainerMetricsPointLong(it.recordedAt, it.netInBytes) },
                netOutBytes = rows.map { ContainerMetricsPointLong(it.recordedAt, it.netOutBytes) }
            )
        )
    }
}