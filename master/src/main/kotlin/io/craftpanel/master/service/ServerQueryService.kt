package io.craftpanel.master.service

import io.craftpanel.master.domain.ServerStatus
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
    @SerialName("net_out_bytes") val netOutBytes: List<ContainerMetricsPointLong>,
    @SerialName("block_in_bytes") val blockInBytes: List<ContainerMetricsPointLong> = emptyList(),
    @SerialName("block_out_bytes") val blockOutBytes: List<ContainerMetricsPointLong> = emptyList(),
    // JVM heap/non-heap series. Rows with no JVM sample are omitted, so these lists are sparser
    // than the others (and empty when JVM metrics are disabled for the server).
    @SerialName("heap_used_bytes") val heapUsedBytes: List<ContainerMetricsPointLong> = emptyList(),
    @SerialName("heap_max_bytes") val heapMaxBytes: List<ContainerMetricsPointLong> = emptyList(),
    @SerialName("non_heap_used_bytes") val nonHeapUsedBytes: List<ContainerMetricsPointLong> = emptyList()
)

@Serializable
data class ServerStatusEventPoint(val status: ServerStatus, @SerialName("recorded_at") val recordedAt: String)

@Serializable
data class ServerStatusHistoryResponse(@SerialName("server_id") val serverId: String, val events: List<ServerStatusEventPoint>)

class ServerQueryService(
    private val serverRepository: ServerRepository,
    private val userRepository: UserRepository,
    private val groupRepository: GroupRepository,
    private val containerMetricsRepository: ContainerMetricsRepository,
    private val migrationRepository: MigrationRepository,
    private val statusHistoryRepository: ServerStatusHistoryRepository
) {

    private val visibilityResolver = ServerVisibilityResolver(userRepository, groupRepository)

    fun isMigrating(id: Uuid): Boolean = migrationRepository.findActiveMigration(id) != null

    fun listServers(userId: Uuid): List<ServerView> {
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

    fun getServer(id: Uuid): ServerView = serverRepository.findById(id) ?: throw NotFoundException("Server not found")

    fun getMetrics(id: Uuid, from: Instant, to: Instant): ContainerMetricsSeriesResponse {
        serverRepository.findById(id) ?: throw NotFoundException("Server not found")
        val rows = containerMetricsRepository.getContainerMetricsByRange(id, from, to)
        return ContainerMetricsSeriesResponse(
            serverId = id.toString(),
            series = ContainerMetricsSeries(
                cpuPercent = rows.map { ContainerMetricsPoint(it.recordedAt, it.cpuPercent) },
                ramUsedMb = rows.map { ContainerMetricsPoint(it.recordedAt, it.ramUsedMb.toDouble()) },
                netInBytes = rows.map { ContainerMetricsPointLong(it.recordedAt, it.netInBytes) },
                netOutBytes = rows.map { ContainerMetricsPointLong(it.recordedAt, it.netOutBytes) },
                blockInBytes = rows.map { ContainerMetricsPointLong(it.recordedAt, it.blockInBytes) },
                blockOutBytes = rows.map { ContainerMetricsPointLong(it.recordedAt, it.blockOutBytes) },
                heapUsedBytes = rows.mapNotNull { it.heapUsedBytes?.let { v -> ContainerMetricsPointLong(it.recordedAt, v) } },
                heapMaxBytes = rows.mapNotNull { it.heapMaxBytes?.let { v -> ContainerMetricsPointLong(it.recordedAt, v) } },
                nonHeapUsedBytes = rows.mapNotNull { it.nonHeapUsedBytes?.let { v -> ContainerMetricsPointLong(it.recordedAt, v) } }
            )
        )
    }

    fun getStatusHistory(id: Uuid, limit: Int): ServerStatusHistoryResponse {
        serverRepository.findById(id) ?: throw NotFoundException("Server not found")
        val events = statusHistoryRepository.listRecent(id, limit)
        return ServerStatusHistoryResponse(
            serverId = id.toString(),
            events = events.map { ServerStatusEventPoint(ServerStatus.fromDb(it.status), it.recordedAt) }
        )
    }
}
