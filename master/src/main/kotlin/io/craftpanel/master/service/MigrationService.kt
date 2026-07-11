package io.craftpanel.master.service

import io.craftpanel.master.dns.DnsProvider
import io.craftpanel.master.domain.MigrationStatus
import io.craftpanel.master.domain.MigrationStepStatus
import io.craftpanel.master.service.migration.MigrationCoordinator
import io.craftpanel.master.service.migration.MigrationPlan
import io.craftpanel.master.service.migration.MigrationRunner
import io.craftpanel.master.service.migration.steps.AllocateRsyncPortStep
import io.craftpanel.master.service.migration.steps.AssignTargetPortStep
import io.craftpanel.master.service.migration.steps.FinalRsyncStep
import io.craftpanel.master.service.migration.steps.InitialRsyncStep
import io.craftpanel.master.service.migration.steps.PlayerWarningStep
import io.craftpanel.master.service.migration.steps.PrepareRsyncReceiveStep
import io.craftpanel.master.service.migration.steps.RemoveSourceContainerStep
import io.craftpanel.master.service.migration.steps.StartTargetContainerStep
import io.craftpanel.master.service.migration.steps.StopSourceStep
import io.craftpanel.master.service.migration.steps.UpdateDnsStep
import io.craftpanel.master.service.migration.steps.UpdateNodeAssignmentStep
import io.craftpanel.master.service.migration.steps.UpdateProxyBackendsStep
import io.craftpanel.master.service.repo.MigrationRepository
import io.craftpanel.master.service.repo.MigrationRow
import io.craftpanel.master.service.repo.MigrationStepRow
import io.craftpanel.master.service.repo.NodeRepository
import io.craftpanel.master.service.repo.PortRepository
import io.craftpanel.master.service.repo.ProxyBackendRepository
import io.craftpanel.master.service.repo.ServerRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

@Serializable
sealed class MigrationEvent {

    @Serializable
    @SerialName("status")
    data class Status(val status: String) : MigrationEvent()

    @Serializable
    @SerialName("step.started")
    data class StepStarted(val step: Int, val description: String) : MigrationEvent()

    @Serializable
    @SerialName("rsync.progress")
    data class RsyncProgress(val isFinalPass: Boolean, val percent: Int, val bytes: Long, val phase: String) : MigrationEvent()

    @Serializable
    @SerialName("failed")
    data class Failed(val error: String) : MigrationEvent()

    @Serializable
    @SerialName("completed")
    data object Completed : MigrationEvent()
}

@Serializable
data class MigrateRequest(
    @SerialName("target_node_id") val targetNodeId: String,
    @SerialName("rsync_image") val rsyncImage: String = "alpine",
    @SerialName("player_warning_message") val playerWarningMessage: String = "Server is restarting in 60 seconds"
)

@Serializable
data class MigrationStepData(
    @SerialName("step_number") val stepNumber: Int,
    val description: String,
    val status: MigrationStepStatus,
    @SerialName("started_at") val startedAt: String?,
    @SerialName("completed_at") val completedAt: String?,
    @SerialName("error_message") val errorMessage: String?
)

@Serializable
data class MigrationResponse(
    val id: String,
    @SerialName("server_id") val serverId: String,
    @SerialName("source_node_id") val sourceNodeId: String,
    @SerialName("target_node_id") val targetNodeId: String,
    val status: MigrationStatus,
    val steps: List<MigrationStepData>,
    @SerialName("created_at") val createdAt: String,
    @SerialName("completed_at") val completedAt: String?
)

class MigrationService(
    private val migrationRepository: MigrationRepository,
    private val serverRepository: ServerRepository,
    private val portRepository: PortRepository,
    private val proxyBackendRepository: ProxyBackendRepository,
    private val nodeRepository: NodeRepository,
    private val gateway: AgentGateway,
    private val dnsProvider: DnsProvider?,
    private val scope: CoroutineScope,
    private val lifecycle: ContainerLifecycle,
    private val serverExposure: ServerExposure,
    private val containerNamePrefix: String = "craftpanel"
) {

    private val eventFlows = ConcurrentHashMap<String, MutableSharedFlow<MigrationEvent>>()

    fun failStuckMigrations() = migrationRepository.failAllStuckMigrations()

    fun startMigration(serverId: Uuid, req: MigrateRequest): MigrationResponse {
        val serverRow = serverRepository.findById(serverId)
            ?: throw NotFoundException("Server not found")

        val targetNodeId = runCatching { Uuid.parse(req.targetNodeId) }.getOrNull()
            ?: throw UnprocessableException("Invalid target_node_id")

        val sourceNodeId = serverRow.nodeId
        if (sourceNodeId == targetNodeId) {
            throw ConflictException("Source and target node are the same")
        }

        val targetNodeRow = nodeRepository.findById(targetNodeId)
            ?: throw NotFoundException("Target node not found")

        if (targetNodeRow.status != "ACTIVE") {
            throw ConflictException("Target node is not ACTIVE")
        }

        val inProgress = migrationRepository.findActiveMigration(serverId) != null
        if (inProgress) throw ConflictException("Migration already in progress for this server")

        val migration = migrationRepository.createMigration(serverId, sourceNodeId, targetNodeId)

        eventFlows[migration.id.toString()] =
            MutableSharedFlow(replay = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        scope.launch {
            runCatching {
                runMigration(
                    migrationId = migration.id,
                    serverId = serverId,
                    sourceNodeId = sourceNodeId,
                    targetNodeId = targetNodeId,
                    rsyncImage = req.rsyncImage,
                    playerWarningMessage = req.playerWarningMessage
                )
            }.onFailure { e ->
                if (e is CancellationException) throw e
                if (e is ExposedSQLException && e.message?.contains("fk_migration_step_log") == true) {
                    log.warn("Migration ${migration.id} aborted: server was deleted mid-flight")
                }
                else {
                    log.error("Migration ${migration.id} crashed unexpectedly", e)
                }
            }
        }

        return getMigration(migration.id)
    }

    fun getMigration(migrationId: Uuid): MigrationResponse {
        val row = migrationRepository.findMigrationById(migrationId)
            ?: throw NotFoundException("Migration not found")
        val steps = migrationRepository.listMigrationSteps(migrationId)
        return row.toResponse(steps)
    }

    fun listMigrations(serverId: Uuid): List<MigrationResponse> = migrationRepository.listMigrations(serverId)
        .map { row -> row.toResponse(migrationRepository.listMigrationSteps(row.id)) }

    fun getEventFlow(migrationId: String): SharedFlow<MigrationEvent>? = eventFlows[migrationId]?.asSharedFlow()

    private suspend fun runMigration(migrationId: Uuid, serverId: Uuid, sourceNodeId: Uuid, targetNodeId: Uuid, rsyncImage: String, playerWarningMessage: String) {
        val migrationIdStr = migrationId.toString()
        val serverIdStr = serverId.toString()
        val sourceNodeIdStr = sourceNodeId.toString()
        val targetNodeIdStr = targetNodeId.toString()
        val eventFlow = eventFlows[migrationIdStr]

        val serverRow = serverRepository.findById(serverId)
            ?: run {
                eventFlow?.emit(MigrationEvent.Failed("Server $serverIdStr no longer exists"))
                return
            }
        val targetNodeRow = nodeRepository.findById(targetNodeId)
            ?: run {
                eventFlow?.emit(MigrationEvent.Failed("Target node $targetNodeIdStr no longer exists"))
                return
            }

        val plan = MigrationPlan(
            migrationId = migrationId,
            migrationIdStr = migrationIdStr,
            serverId = serverId,
            serverIdStr = serverIdStr,
            sourceNodeId = sourceNodeId,
            sourceNodeIdStr = sourceNodeIdStr,
            targetNodeId = targetNodeId,
            targetNodeIdStr = targetNodeIdStr,
            rsyncImage = rsyncImage,
            playerWarningMessage = playerWarningMessage,
            containerNamePrefix = containerNamePrefix,
            serverRow = serverRow,
            targetNodeRow = targetNodeRow,
            targetPrivateIp = targetNodeRow.privateIp
        )

        val coord = MigrationCoordinator(
            migrationRepository = migrationRepository,
            serverRepository = serverRepository,
            portRepository = portRepository,
            proxyBackendRepository = proxyBackendRepository,
            nodeRepository = nodeRepository,
            gateway = gateway,
            dnsProvider = dnsProvider,
            lifecycle = lifecycle,
            serverExposure = serverExposure,
            scope = scope,
            eventFlow = eventFlow
        )

        val steps = listOf(
            AllocateRsyncPortStep(),
            PrepareRsyncReceiveStep(),
            InitialRsyncStep(),
            PlayerWarningStep(),
            StopSourceStep(),
            FinalRsyncStep(),
            RemoveSourceContainerStep(),
            AssignTargetPortStep(),
            StartTargetContainerStep(),
            UpdateDnsStep(),
            UpdateNodeAssignmentStep(),
            UpdateProxyBackendsStep()
        )

        MigrationRunner(steps, plan, coord).run()
    }

    private val log = org.slf4j.LoggerFactory.getLogger(MigrationService::class.java)
}

private fun MigrationStepRow.toStepData() = MigrationStepData(
    stepNumber = this.stepNumber,
    description = this.description,
    status = MigrationStepStatus.fromDb(this.status),
    startedAt = this.startedAt,
    completedAt = this.completedAt,
    errorMessage = this.errorMessage
)

private fun MigrationRow.toResponse(steps: List<MigrationStepRow>) = MigrationResponse(
    id = this.id.toString(),
    serverId = this.serverId.toString(),
    sourceNodeId = this.sourceNodeId.toString(),
    targetNodeId = this.targetNodeId.toString(),
    status = MigrationStatus.fromDb(this.status),
    steps = steps.map { it.toStepData() },
    createdAt = this.createdAt,
    completedAt = this.completedAt
)
