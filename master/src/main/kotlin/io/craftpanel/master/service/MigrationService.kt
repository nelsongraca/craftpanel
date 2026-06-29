package io.craftpanel.master.service

import io.craftpanel.proto.*
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.domain.MigrationStatus
import io.craftpanel.master.domain.MigrationStepStatus
import io.craftpanel.master.dns.DnsProvider
import io.craftpanel.master.service.repo.MigrationStepRow
import io.craftpanel.master.service.repo.NodeRepository
import io.craftpanel.master.service.repo.NetworkRepository
import io.craftpanel.master.service.repo.ServerRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
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
    @SerialName("player_warning_message") val playerWarningMessage: String = "Server is restarting in 60 seconds",
)

@Serializable
data class MigrationStepData(
    @SerialName("step_number") val stepNumber: Int,
    val description: String,
    val status: MigrationStepStatus,
    @SerialName("started_at") val startedAt: String?,
    @SerialName("completed_at") val completedAt: String?,
    @SerialName("error_message") val errorMessage: String?,
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
    @SerialName("completed_at") val completedAt: String?,
)

class MigrationService(
    private val serverRepository: ServerRepository,
    private val nodeRepository: NodeRepository,
    private val networkRepository: NetworkRepository,
    private val gateway: AgentGateway,
    private val dnsProvider: DnsProvider?,
    private val scope: CoroutineScope,
    private val lifecycle: ContainerLifecycle,
    private val containerNamePrefix: String = "craftpanel",
) {


    private val eventFlows = ConcurrentHashMap<String, MutableSharedFlow<MigrationEvent>>()

    fun failStuckMigrations() = serverRepository.failAllStuckMigrations()

    fun startMigration(serverId: Uuid, req: MigrateRequest): MigrationResponse {
        val serverRow = serverRepository.findById(serverId)
            ?: throw NotFoundException("Server not found")

        val targetNodeId = runCatching { Uuid.parse(req.targetNodeId) }.getOrNull()
            ?: throw UnprocessableException("Invalid target_node_id")

        val sourceNodeId = serverRow.nodeId
        if (sourceNodeId == targetNodeId)
            throw ConflictException("Source and target node are the same")

        val targetNodeRow = nodeRepository.findById(targetNodeId)
            ?: throw NotFoundException("Target node not found")

        if (targetNodeRow.status != "ACTIVE")
            throw ConflictException("Target node is not ACTIVE")

        val inProgress = serverRepository.findActiveMigration(serverId) != null
        if (inProgress) throw ConflictException("Migration already in progress for this server")

        val migration = serverRepository.createMigration(serverId, sourceNodeId, targetNodeId)

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
                    playerWarningMessage = req.playerWarningMessage,
                )
            }.onFailure { e ->
                if (e is CancellationException) throw e
                if (e is ExposedSQLException && e.message?.contains("fk_migration_step_log") == true) {
                    log.warn("Migration ${migration.id} aborted: server was deleted mid-flight")
                } else {
                    log.error("Migration ${migration.id} crashed unexpectedly", e)
                }
            }
        }

        return getMigration(migration.id)
    }

    fun getMigration(migrationId: Uuid): MigrationResponse {
        val row = serverRepository.findMigrationById(migrationId)
            ?: throw NotFoundException("Migration not found")
        val steps = serverRepository.listMigrationSteps(migrationId)
        return MigrationResponse(
            id = migrationId.toString(),
            serverId = row.serverId.toString(),
            sourceNodeId = row.sourceNodeId.toString(),
            targetNodeId = row.targetNodeId.toString(),
            status = MigrationStatus.fromDb(row.status),
            steps = steps.map { it.toStepData() },
            createdAt = row.createdAt,
            completedAt = row.completedAt,
        )
    }

    fun listMigrations(serverId: Uuid): List<MigrationResponse> =
        serverRepository.listMigrations(serverId)
            .map { row ->
                val steps = serverRepository.listMigrationSteps(row.id)
                MigrationResponse(
                    id = row.id.toString(),
                    serverId = row.serverId.toString(),
                    sourceNodeId = row.sourceNodeId.toString(),
                    targetNodeId = row.targetNodeId.toString(),
                    status = MigrationStatus.fromDb(row.status),
                    steps = steps.map { it.toStepData() },
                    createdAt = row.createdAt,
                    completedAt = row.completedAt,
                )
            }

    fun getEventFlow(migrationId: String): SharedFlow<MigrationEvent>? =
        eventFlows[migrationId]?.asSharedFlow()

    private suspend fun runMigration(
        migrationId: Uuid,
        serverId: Uuid,
        sourceNodeId: Uuid,
        targetNodeId: Uuid,
        rsyncImage: String,
        playerWarningMessage: String,
    ) {
        val migrationIdStr = migrationId.toString()
        val serverIdStr = serverId.toString()
        val eventFlow = eventFlows[migrationIdStr]
        fun now() = Clock.System.now()

        suspend fun emit(event: MigrationEvent) = eventFlow?.emit(event)

        fun updateStatus(status: MigrationStatus) {
            val ts = now()
            serverRepository.updateMigrationStatus(migrationId, status, if (status == MigrationStatus.COMPLETED || status == MigrationStatus.FAILED) ts else null)
            scope.launch { emit(MigrationEvent.Status(status.name)) }
        }

        fun startStep(stepNum: Int, description: String): Uuid {
            val step = serverRepository.createMigrationStep(migrationId, stepNum, description)
            serverRepository.updateMigrationStepStatus(step.id, MigrationStepStatus.RUNNING, now(), null, null)
            scope.launch { emit(MigrationEvent.StepStarted(stepNum, description)) }
            return step.id
        }

        fun completeStep(stepId: Uuid, success: Boolean, error: String? = null) {
            serverRepository.updateMigrationStepStatus(stepId, if (success) MigrationStepStatus.SUCCESS else MigrationStepStatus.FAILED, null, now(), error)
        }

        suspend fun failMigration(error: String) {
            updateStatus(MigrationStatus.FAILED)
            emit(MigrationEvent.Failed(error))
        }

        // ── Collect source/target node data ──────────────────────────────────
        val sourceNodeIdStr = sourceNodeId.toString()
        val targetNodeIdStr = targetNodeId.toString()

        val serverRow = serverRepository.findById(serverId)
            ?: run { failMigration("Server $serverIdStr no longer exists"); return }
        val targetNodeRow = nodeRepository.findById(targetNodeId)
            ?: run { failMigration("Target node $targetNodeIdStr no longer exists"); return }

        val targetPrivateIp = targetNodeRow.privateIp

        // ── Step 1: Allocate rsync port ──────────────────────────────────────
        val rsyncPort: Int
        run {
            val stepId = startStep(1, "Allocate rsync port on target node")
            try {
                rsyncPort = allocateRsyncPort(targetNodeId)
                completeStep(stepId, true)
            }
            catch (e: Exception) {
                completeStep(stepId, false, e.message)
                failMigration("Port allocation failed: ${e.message}")
                return
            }
        }

        try {
            // ── Step 2: PrepareRsyncReceive → target, await RsyncReadyUpdate ─────
            val rsyncPassword: String
            run {
                val stepId = startStep(2, "Prepare rsync receiver on target node")
                val readyChannel = Channel<AgentEvent.RsyncReadyEvent>(1)
                val job = scope.launch {
                    gateway.agentEvents.filterIsInstance<AgentEvent.RsyncReadyEvent>()
                        .collect { if (it.migrationId == migrationIdStr) readyChannel.trySend(it) }
                }
                try {
                    val sent = gateway.sendToNode(targetNodeIdStr, masterMessage {
                        prepareRsyncReceive = prepareRsyncReceiveCommand {
                            this.migrationId = migrationIdStr
                            this.serverId = serverIdStr
                            port = rsyncPort
                            this.rsyncImage = rsyncImage
                        }
                    })
                    if (!sent) {
                        completeStep(stepId, false, "Target agent not connected")
                        failMigration("Target agent not connected")
                        return
                    }
                    val ready = withTimeoutOrNull(60.seconds) { readyChannel.receive() }
                    if (ready == null) {
                        completeStep(stepId, false, "Timeout waiting for rsync receiver")
                        failMigration("Timeout waiting for rsync receiver on target")
                        return
                    }
                    rsyncPassword = ready.rsyncPassword
                    completeStep(stepId, true)
                }
                finally {
                    job.cancel()
                    readyChannel.close()
                }
            }

            // ── Step 3: Initial rsync pass (server still running) ────────────────
            run {
                val stepId = startStep(3, "Initial rsync pass (live data sync)")
                val completeChannel = Channel<AgentEvent.RsyncCompleteEvent>(1)
                val progressJob = scope.launch {
                    gateway.agentEvents.filterIsInstance<AgentEvent.RsyncProgressEvent>()
                        .collect { u ->
                            if (u.migrationId == migrationIdStr && !u.isFinalPass) {
                                emit(MigrationEvent.RsyncProgress(false, u.percentComplete, u.bytesTransferred, u.phase))
                            }
                        }
                }
                val completeJob = scope.launch {
                    gateway.agentEvents.filterIsInstance<AgentEvent.RsyncCompleteEvent>()
                        .collect { u ->
                            if (u.migrationId == migrationIdStr && !u.isFinalPass) completeChannel.trySend(u)
                        }
                }
                try {
                    val sent = gateway.sendToNode(sourceNodeIdStr, masterMessage {
                        startRsync = startRsyncCommand {
                            this.migrationId = migrationIdStr
                            this.serverId = serverIdStr
                            destinationIp = targetPrivateIp
                            destinationPort = rsyncPort
                            this.rsyncPassword = rsyncPassword
                            this.rsyncImage = rsyncImage
                            isFinalPass = false
                        }
                    })
                    if (!sent) {
                        completeStep(stepId, false, "Source agent not connected")
                        failMigration("Source agent not connected")
                        return
                    }
                    val complete = withTimeoutOrNull(3600.seconds) { completeChannel.receive() }
                    if (complete == null || !complete.success) {
                        val err = complete?.errorMessage ?: "Timeout waiting for initial rsync"
                        completeStep(stepId, false, err)
                        failMigration("Initial rsync failed: $err")
                        return
                    }
                    completeStep(stepId, true)
                }
                finally {
                    progressJob.cancel()
                    completeJob.cancel()
                    completeChannel.close()
                }
            }

            updateStatus(MigrationStatus.SYNCING)

            // ── Step 4: Player warning via RCON ──────────────────────────────────
            run {
                val stepId = startStep(4, "Broadcast player warning via RCON")
                val safeMsg = playerWarningMessage
                    .replace('\n', ' ')
                    .replace('\r', ' ')
                    .replace("\"", "")
                    .take(255)
                gateway.sendToNode(sourceNodeIdStr, masterMessage {
                    sendRcon = sendRconCommand {
                        this.serverId = serverIdStr
                        command = "say $safeMsg"
                    }
                })
                completeStep(stepId, true)
            }

            delay(2.seconds)

            // ── Step 5: Stop source container ──────────────────────────────────
            var sourceStopped = false
            run {
                val stepId = startStep(5, "Stop source server container")
                val alreadyStopped = serverRow.status == "STOPPED"
                if (!alreadyStopped) {
                    runCatching { lifecycle.stop(serverRow, sourceNodeIdStr) }
                        .onFailure { e ->
                            completeStep(stepId, false, e.message)
                            failMigration("Source server did not stop: ${e.message}")
                            return
                        }
                }
                sourceStopped = true
                completeStep(stepId, true)
            }

            fun restartSource() {
                if (sourceStopped) scope.launch {
                    runCatching { lifecycle.start(serverRow, needsRecreate = false, nodeId = sourceNodeIdStr) }
                }
            }

            // ── Step 6: Final rsync pass (source stopped → consistent snapshot) ──
            run {
                val stepId = startStep(6, "Final rsync pass (delta sync, source stopped)")
                val completeChannel = Channel<AgentEvent.RsyncCompleteEvent>(1)
                val progressJob = scope.launch {
                    gateway.agentEvents.filterIsInstance<AgentEvent.RsyncProgressEvent>()
                        .collect { u ->
                            if (u.migrationId == migrationIdStr && u.isFinalPass) {
                                emit(MigrationEvent.RsyncProgress(true, u.percentComplete, u.bytesTransferred, u.phase))
                            }
                        }
                }
                val completeJob = scope.launch {
                    gateway.agentEvents.filterIsInstance<AgentEvent.RsyncCompleteEvent>()
                        .collect { u ->
                            if (u.migrationId == migrationIdStr && u.isFinalPass) completeChannel.trySend(u)
                        }
                }
                try {
                    gateway.sendToNode(sourceNodeIdStr, masterMessage {
                        startRsync = startRsyncCommand {
                            this.migrationId = migrationIdStr
                            this.serverId = serverIdStr
                            destinationIp = targetPrivateIp
                            destinationPort = rsyncPort
                            this.rsyncPassword = rsyncPassword
                            this.rsyncImage = rsyncImage
                            isFinalPass = true
                        }
                    })
                    val complete = withTimeoutOrNull(600.seconds) { completeChannel.receive() }
                    if (complete == null || !complete.success) {
                        val err = complete?.errorMessage ?: "Timeout waiting for final rsync"
                        completeStep(stepId, false, err)
                        restartSource()
                        failMigration("Final rsync failed: $err")
                        return
                    }
                    completeStep(stepId, true)
                }
                finally {
                    progressJob.cancel()
                    completeJob.cancel()
                    completeChannel.close()
                }
            }

            updateStatus(MigrationStatus.CUTTING_OVER)

            // ── Step 7: Remove source container ──────────────────────────────────
            run {
                val stepId = startStep(7, "Remove source server container")
                runCatching { lifecycle.remove(serverRow, sourceNodeIdStr) }
                    .onFailure { e ->
                        completeStep(stepId, false, e.message)
                        restartSource()
                        failMigration("Source container removal failed: ${e.message}")
                        return
                    }
                sourceStopped = false
                completeStep(stepId, true)
            }

            // ── Port assignment on target ─────────────────────────────────────────
            val existingPort = serverRow.hostPort
            val usedPorts = serverRepository.findUsedPortsOnNode(targetNodeId)
                .toSet()

            val assignedPort = if (existingPort in usedPorts) {
                val range = targetNodeRow.portRangeStart..targetNodeRow.portRangeEnd
                range.firstOrNull { it !in usedPorts }
                    ?: throw PortExhaustedException("No free ports on target node")
            }
            else existingPort

            serverRepository.releasePortsForServer(serverId)
            serverRepository.registerPort(targetNodeId, assignedPort, "TCP", serverId)

            if (assignedPort != existingPort) {
                serverRepository.updateMigrationHostPort(serverId, assignedPort)
            }

            val freshServerRow = serverRepository.findById(serverId)!!

            // ── Step 8: Create and start container on target ─────────────────────
            run {
                val stepId = startStep(8, "Create and start server container on target node")
                runCatching {
                    lifecycle.start(freshServerRow, needsRecreate = true, nodeId = targetNodeIdStr)
                }.onFailure { e ->
                    runCatching { lifecycle.remove(freshServerRow, targetNodeIdStr, force = true) }
                    completeStep(stepId, false, e.message)
                    failMigration("New container failed to start on target: ${e.message}")
                    return
                }
                completeStep(stepId, true)
            }

            // ── Step 9: Update DNS A record ──────────────────────────────────────
            run {
                val stepId = startStep(9, "Update DNS A record to target node IP")
                val recordId = serverRow.dnsRecordId
                val provider = dnsProvider
                if (recordId != null && provider != null) {
                    val networkId = serverRow.networkId
                    val dns = resolveNetworkDnsForMigration(networkId)
                    if (dns != null) {
                        runCatching {
                            provider.updateARecord(dns.zoneId, recordId, targetNodeRow.publicIp)
                        }.onFailure { ex ->
                            completeStep(stepId, false, ex.message)
                            failMigration("DNS update failed: ${ex.message}")
                            return
                        }
                    }
                }
                completeStep(stepId, true)
            }

            // ── Step 10: mc-router labels updated implicitly at container creation ─
            // Nothing to do — labels set in step 8 CreateContainerCommand.

            // ── Step 11: Update DB — node assignment ─────────────────────────────
            run {
                val stepId = startStep(11, "Update server node assignment in database")
                try {
                    serverRepository.updateNodeId(serverId, targetNodeId)
                    serverRepository.releasePort(targetNodeId, rsyncPort, "TCP")
                    completeStep(stepId, true)
                }
                catch (e: Exception) {
                    completeStep(stepId, false, e.message)
                    failMigration("DB update failed: ${e.message}")
                    return
                }
            }

            // ── Step 12: Update proxy backends for networks ───────────────────────
            run {
                val stepId = startStep(12, "Update proxy backends")
                runCatching {
                    updateProxyBackendsAfterMigration(serverId, targetNodeRow.privateIp, freshServerRow.hostPort)
                }.onFailure { ex ->
                    log.warn("Proxy backend update failed after migration $migrationIdStr: ${ex.message}")
                }
                completeStep(stepId, true)
            }

            updateStatus(MigrationStatus.COMPLETED)
            emit(MigrationEvent.Completed)
        }
        finally {
            // Always clean up rsync-recv container and release the ephemeral rsync port.
            runCatching {
                gateway.sendToNode(targetNodeIdStr, masterMessage {
                    removeContainer = removeContainerCommand {
                        containerName = "$containerNamePrefix-rsync-recv-$migrationIdStr"
                        force = true
                    }
                })
            }
            serverRepository.releasePort(targetNodeId, rsyncPort, "TCP")
        }
    }

    private fun allocateRsyncPort(targetNodeId: Uuid): Int {
        val usedPorts = serverRepository.findUsedPortsOnNode(targetNodeId)
            .toSet()
        val node = nodeRepository.findById(targetNodeId)
            ?: throw NotFoundException("Target node $targetNodeId not found")
        val port = (node.portRangeStart..node.portRangeEnd)
            .firstOrNull { it !in usedPorts }
            ?: throw PortExhaustedException("No free ports in range ${node.portRangeStart}-${node.portRangeEnd} on node $targetNodeId")
        serverRepository.registerPort(targetNodeId, port, "TCP", null)
        return port
    }

    private fun resolveNetworkDnsForMigration(networkId: Uuid?): NetworkDns? {
        if (networkId == null) return null
        return networkRepository.findById(networkId)
            ?.let {
                val zoneId = it.cfZoneId ?: return null
                val suffix = it.cfDomainSuffix ?: return null
                NetworkDns(zoneId, suffix)
            }
    }

    private data class NetworkDns(val zoneId: String, val domainSuffix: String)

    private fun updateProxyBackendsAfterMigration(serverId: Uuid, targetIp: String, port: Int) {
        val proxyServerIds = serverRepository.findProxyServersForBackend(serverId)
        if (proxyServerIds.isEmpty()) return
        for (proxyServerId in proxyServerIds) {
            val proxyServer = serverRepository.findById(proxyServerId) ?: continue
            val nodeIdStr = proxyServer.nodeId.toString()
            val sent = gateway.sendToNode(nodeIdStr, masterMessage {
                restartContainer = restartContainerCommand { this.serverId = proxyServerId.toString() }
            })
            if (sent) {
                log.info("Triggered proxy restart for server $proxyServerId on node $nodeIdStr after migration of $serverId to $targetIp:$port")
            }
            else {
                log.warn("Could not reach node $nodeIdStr to restart proxy $proxyServerId after migration of $serverId — manual restart may be required")
            }
        }
    }

    private val log = org.slf4j.LoggerFactory.getLogger(MigrationService::class.java)
}

private fun MigrationStepRow.toStepData() = MigrationStepData(
    stepNumber = this.stepNumber,
    description = this.description,
    status = MigrationStepStatus.fromDb(this.status),
    startedAt = this.startedAt,
    completedAt = this.completedAt,
    errorMessage = this.errorMessage,
)
