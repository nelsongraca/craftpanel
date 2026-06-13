package io.craftpanel.master.service

import io.craftpanel.proto.*
import io.craftpanel.master.database.schema.*
import io.craftpanel.master.dns.DnsProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import io.craftpanel.master.util.toUtcString

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
    val status: String,
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
    val status: String,
    val steps: List<MigrationStepData>,
    @SerialName("created_at") val createdAt: String,
    @SerialName("completed_at") val completedAt: String?,
)

class MigrationService(
    private val sendToNode: (String, MasterMessage) -> Boolean,
    private val rsyncReadyFlow: SharedFlow<RsyncReadyUpdate>,
    private val rsyncProgressFlow: SharedFlow<RsyncProgressUpdate>,
    private val rsyncCompleteFlow: SharedFlow<RsyncCompleteUpdate>,
    private val serverStatusFlow: SharedFlow<Pair<String, ServerStatusUpdate>>,
    private val dnsProvider: DnsProvider?,
    private val scope: CoroutineScope,
    private val lifecycle: ServerLifecycle,
    private val containerNamePrefix: String = "craftpanel",
) {


    private val eventFlows = ConcurrentHashMap<String, MutableSharedFlow<MigrationEvent>>()

    fun failStuckMigrations() = transaction {
        ServerMigrations.update({
            ServerMigrations.status inList listOf("PENDING", "SYNCING", "CUTTING_OVER", "RUNNING")
        }) {
            it[ServerMigrations.status] = "FAILED"
            it[ServerMigrations.completedAt] = Clock.System.now()
                .toLocalDateTime(TimeZone.UTC)
        }
    }

    fun startMigration(serverId: Uuid, req: MigrateRequest): MigrationResponse {
        val serverRow = transaction {
            Servers.selectAll()
                .where { Servers.id eq serverId }
                .firstOrNull()
        } ?: throw NotFoundException("Server not found")

        val targetNodeId = runCatching { Uuid.parse(req.targetNodeId) }.getOrNull()
            ?: throw UnprocessableException("Invalid target_node_id")

        val sourceNodeId = serverRow[Servers.nodeId]
        if (sourceNodeId == targetNodeId)
            throw ConflictException("Source and target node are the same")

        val targetNodeRow = transaction {
            Nodes.selectAll()
                .where { Nodes.id eq targetNodeId }
                .firstOrNull()
        } ?: throw NotFoundException("Target node not found")

        if (targetNodeRow[Nodes.status] != "ACTIVE")
            throw ConflictException("Target node is not ACTIVE")

        val inProgress = transaction {
            ServerMigrations.selectAll()
                .where {
                    (ServerMigrations.serverId eq serverId) and
                            (ServerMigrations.status inList listOf("PENDING", "SYNCING", "CUTTING_OVER"))
                }
                .any()
        }
        if (inProgress) throw ConflictException("Migration already in progress for this server")

        val migrationId = transaction {
            ServerMigrations.insert {
                it[ServerMigrations.serverId] = serverId
                it[ServerMigrations.sourceNodeId] = sourceNodeId
                it[ServerMigrations.targetNodeId] = targetNodeId
                it[ServerMigrations.status] = "PENDING"
            }[ServerMigrations.id]
        }

        eventFlows[migrationId.toString()] =
            MutableSharedFlow(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        scope.launch {
            runMigration(
                migrationId = migrationId,
                serverId = serverId,
                sourceNodeId = sourceNodeId,
                targetNodeId = targetNodeId,
                rsyncImage = req.rsyncImage,
                playerWarningMessage = req.playerWarningMessage,
            )
        }

        return getMigration(migrationId)
    }

    fun getMigration(migrationId: Uuid): MigrationResponse {
        val row = transaction {
            ServerMigrations.selectAll()
                .where { ServerMigrations.id eq migrationId }
                .firstOrNull()
        } ?: throw NotFoundException("Migration not found")
        val steps = transaction {
            MigrationStepLog.selectAll()
                .where { MigrationStepLog.migrationId eq migrationId }
                .orderBy(MigrationStepLog.stepNumber, SortOrder.ASC)
                .map { it.toStepData() }
        }
        return MigrationResponse(
            id = migrationId.toString(),
            serverId = row[ServerMigrations.serverId].toString(),
            sourceNodeId = row[ServerMigrations.sourceNodeId].toString(),
            targetNodeId = row[ServerMigrations.targetNodeId].toString(),
            status = row[ServerMigrations.status],
            steps = steps,
            createdAt = row[ServerMigrations.createdAt].toUtcString(),
            completedAt = row[ServerMigrations.completedAt]?.toUtcString(),
        )
    }

    fun listMigrations(serverId: Uuid): List<MigrationResponse> =
        transaction {
            ServerMigrations.selectAll()
                .where { ServerMigrations.serverId eq serverId }
                .orderBy(ServerMigrations.createdAt, SortOrder.DESC)
                .map { row ->
                    val migId = row[ServerMigrations.id]
                    val steps = MigrationStepLog.selectAll()
                        .where { MigrationStepLog.migrationId eq migId }
                        .orderBy(MigrationStepLog.stepNumber, SortOrder.ASC)
                        .map { it.toStepData() }
                    MigrationResponse(
                        id = migId.toString(),
                        serverId = row[ServerMigrations.serverId].toString(),
                        sourceNodeId = row[ServerMigrations.sourceNodeId].toString(),
                        targetNodeId = row[ServerMigrations.targetNodeId].toString(),
                        status = row[ServerMigrations.status],
                        steps = steps,
                        createdAt = row[ServerMigrations.createdAt].toUtcString(),
                        completedAt = row[ServerMigrations.completedAt]?.toUtcString(),
                    )
                }
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
            .toLocalDateTime(TimeZone.UTC)

        suspend fun emit(event: MigrationEvent) = eventFlow?.emit(event)

        fun updateStatus(status: String) {
            transaction {
                ServerMigrations.update({ ServerMigrations.id eq migrationId }) {
                    it[ServerMigrations.status] = status
                    if (status == "COMPLETED" || status == "FAILED") it[ServerMigrations.completedAt] = now()
                }
            }
            scope.launch { emit(MigrationEvent.Status(status)) }
        }

        fun startStep(stepNum: Int, description: String): Uuid {
            val stepId = transaction {
                MigrationStepLog.insert {
                    it[MigrationStepLog.migrationId] = migrationId
                    it[MigrationStepLog.stepNumber] = stepNum
                    it[MigrationStepLog.description] = description
                    it[MigrationStepLog.status] = "RUNNING"
                    it[MigrationStepLog.startedAt] = now()
                }[MigrationStepLog.id]
            }
            scope.launch { emit(MigrationEvent.StepStarted(stepNum, description)) }
            return stepId
        }

        fun completeStep(stepId: Uuid, success: Boolean, error: String? = null) {
            transaction {
                MigrationStepLog.update({ MigrationStepLog.id eq stepId }) {
                    it[MigrationStepLog.status] = if (success) "SUCCESS" else "FAILED"
                    it[MigrationStepLog.completedAt] = now()
                    it[MigrationStepLog.errorMessage] = error
                }
            }
        }

        suspend fun failMigration(error: String) {
            updateStatus("FAILED")
            emit(MigrationEvent.Failed(error))
        }

        // ── Collect source/target node data ──────────────────────────────────
        val sourceNodeIdStr = sourceNodeId.toString()
        val targetNodeIdStr = targetNodeId.toString()

        val serverRow = transaction {
            Servers.selectAll()
                .where { Servers.id eq serverId }
                .firstOrNull()
        }
            ?: run { failMigration("Server $serverIdStr no longer exists"); return }
        val targetNodeRow = transaction {
            Nodes.selectAll()
                .where { Nodes.id eq targetNodeId }
                .firstOrNull()
        }
            ?: run { failMigration("Target node $targetNodeIdStr no longer exists"); return }

        val targetPrivateIp = targetNodeRow[Nodes.privateIp]

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
                val readyChannel = Channel<RsyncReadyUpdate>(1)
                val job = scope.launch {
                    rsyncReadyFlow.collect { if (it.migrationId == migrationIdStr) readyChannel.trySend(it) }
                }
                try {
                    val sent = sendToNode(targetNodeIdStr, masterMessage {
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
                val completeChannel = Channel<RsyncCompleteUpdate>(1)
                val progressJob = scope.launch {
                    rsyncProgressFlow.collect { u ->
                        if (u.migrationId == migrationIdStr && !u.isFinalPass) {
                            emit(MigrationEvent.RsyncProgress(false, u.percentComplete, u.bytesTransferred, u.phase))
                        }
                    }
                }
                val completeJob = scope.launch {
                    rsyncCompleteFlow.collect { u ->
                        if (u.migrationId == migrationIdStr && !u.isFinalPass) completeChannel.trySend(u)
                    }
                }
                try {
                    val sent = sendToNode(sourceNodeIdStr, masterMessage {
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

            updateStatus("SYNCING")

            // ── Step 4: Player warning via RCON ──────────────────────────────────
            run {
                val stepId = startStep(4, "Broadcast player warning via RCON")
                val safeMsg = playerWarningMessage
                    .replace('\n', ' ')
                    .replace('\r', ' ')
                    .replace("\"", "")
                    .take(255)
                sendToNode(sourceNodeIdStr, masterMessage {
                    sendRcon = sendRconCommand {
                        this.serverId = serverIdStr
                        command = "say $safeMsg"
                    }
                })
                completeStep(stepId, true)
            }

            delay(2.seconds)

            // ── Step 5: save-all then save-off via RCON ──────────────────────────
            run {
                val stepId = startStep(5, "Flush and disable auto-save via RCON")
                val saveAllSent = sendToNode(sourceNodeIdStr, masterMessage {
                    sendRcon = sendRconCommand {
                        this.serverId = serverIdStr
                        command = "save-all"
                    }
                })
                if (!saveAllSent) {
                    completeStep(stepId, false, "Agent disconnected before save-all")
                    failMigration("Failed to send save-all RCON: agent disconnected")
                    return
                }
                delay(500.milliseconds)
                val saveOffSent = sendToNode(sourceNodeIdStr, masterMessage {
                    sendRcon = sendRconCommand {
                        this.serverId = serverIdStr
                        command = "save-off"
                    }
                })
                if (!saveOffSent) {
                    completeStep(stepId, false, "Agent disconnected before save-off")
                    failMigration("Failed to send save-off RCON: agent disconnected")
                    return
                }
                completeStep(stepId, true)
            }

            // ── Step 6: Final rsync pass (delta only, server still up) ───────────
            run {
                val stepId = startStep(6, "Final rsync pass (delta sync)")
                val completeChannel = Channel<RsyncCompleteUpdate>(1)
                val progressJob = scope.launch {
                    rsyncProgressFlow.collect { u ->
                        if (u.migrationId == migrationIdStr && u.isFinalPass) {
                            emit(MigrationEvent.RsyncProgress(true, u.percentComplete, u.bytesTransferred, u.phase))
                        }
                    }
                }
                val completeJob = scope.launch {
                    rsyncCompleteFlow.collect { u ->
                        if (u.migrationId == migrationIdStr && u.isFinalPass) completeChannel.trySend(u)
                    }
                }
                try {
                    sendToNode(sourceNodeIdStr, masterMessage {
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

            updateStatus("CUTTING_OVER")

            // ── Step 7: Create and start container on target ─────────────────────
            run {
                val stepId = startStep(7, "Create and start server container on target node")

                val readyChannel = Channel<String>(1)
                val statusJob = scope.launch {
                    serverStatusFlow.collect { (sId, update) ->
                        if (sId == serverIdStr && update.status == ServerStatusUpdate.ServerStatus.HEALTHY)
                            readyChannel.trySend("HEALTHY")
                    }
                }
                try {
                    val healthy = lifecycle.relocate(
                        server = serverRow,
                        fromNode = sourceNodeIdStr,
                        toNode = targetNodeIdStr,
                        interCmdDelay = 1.seconds,
                        awaitHealthy = {
                            withTimeoutOrNull(120.seconds) { readyChannel.receive() } != null
                        },
                    )
                    if (!healthy) {
                        sendToNode(targetNodeIdStr, masterMessage {
                            removeContainer = removeContainerCommand {
                                containerName = "$containerNamePrefix-$serverId"
                                force = true
                            }
                        })
                        completeStep(stepId, false, "Timeout waiting for container to become HEALTHY")
                        failMigration("New container failed to start on target")
                        return
                    }
                    completeStep(stepId, true)
                }
                finally {
                    statusJob.cancel()
                    readyChannel.close()
                }
            }

            // ── Step 8: Update DNS A record ──────────────────────────────────────
            run {
                val stepId = startStep(8, "Update DNS A record to target node IP")
                val recordId = serverRow[Servers.dnsRecordId]
                val provider = dnsProvider
                if (recordId != null && provider != null) {
                    val networkId = serverRow[Servers.networkId]
                    val dns = resolveNetworkDnsForMigration(networkId)
                    if (dns != null) {
                        runCatching {
                            provider.updateARecord(dns.zoneId, recordId, targetNodeRow[Nodes.publicIp])
                        }.onFailure { ex ->
                            completeStep(stepId, false, ex.message)
                            failMigration("DNS update failed: ${ex.message}")
                            return
                        }
                    }
                }
                completeStep(stepId, true)
            }

            // ── Step 9: mc-router labels updated implicitly at container creation ─
            // Nothing to do — labels set in step 7 CreateContainerCommand.

            // ── Step 10: (removed — source container already stopped/removed in step 7) ─

            // ── Step 11: Update DB — node assignment + PortRegistry ──────────────
            run {
                val stepId = startStep(11, "Update server node assignment in database")
                try {
                    transaction {
                        Servers.update({ Servers.id eq serverId }) {
                            it[Servers.nodeId] = targetNodeId
                            it[Servers.updatedAt] = now()
                        }
                        // Move the server's port entry to the target node
                        val serverPort = serverRow[Servers.hostPort]
                        PortRegistry.deleteWhere { (PortRegistry.serverId eq serverId) }
                        PortRegistry.insert {
                            it[PortRegistry.nodeId] = targetNodeId
                            it[PortRegistry.port] = serverPort
                            it[PortRegistry.protocol] = "TCP"
                            it[PortRegistry.serverId] = serverId
                        }
                        // Release rsync ephemeral port
                        PortRegistry.deleteWhere {
                            (PortRegistry.nodeId eq targetNodeId) and
                                    (PortRegistry.port eq rsyncPort) and
                                    (PortRegistry.serverId.isNull())
                        }
                    }
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
                    updateProxyBackendsAfterMigration(serverId, targetNodeRow[Nodes.privateIp], serverRow[Servers.hostPort])
                }.onFailure { ex ->
                    log.warn("Proxy backend update failed after migration $migrationIdStr: ${ex.message}")
                }
                completeStep(stepId, true)
            }

            updateStatus("COMPLETED")
            emit(MigrationEvent.Completed)
        }
        finally {
            // Always clean up rsync-recv container and release the ephemeral rsync port.
            // On success path the port was already freed in step 11 — the deleteWhere is a no-op.
            runCatching {
                sendToNode(targetNodeIdStr, masterMessage {
                    removeContainer = removeContainerCommand {
                        containerName = "$containerNamePrefix-rsync-recv-$migrationIdStr"
                        force = true
                    }
                })
            }
            transaction {
                PortRegistry.deleteWhere {
                    (PortRegistry.nodeId eq targetNodeId) and
                            (PortRegistry.port eq rsyncPort) and
                            (PortRegistry.serverId.isNull())
                }
            }
        }
    }

    private fun allocateRsyncPort(targetNodeId: Uuid): Int = transaction {
        val usedPorts = PortRegistry.selectAll()
            .where { (PortRegistry.nodeId eq targetNodeId) and (PortRegistry.protocol eq "TCP") }
            .map { it[PortRegistry.port] }
            .toSet()
        val node = Nodes.selectAll()
            .where { Nodes.id eq targetNodeId }
            .firstOrNull() ?: throw NotFoundException("Target node $targetNodeId not found")
        val start = node[Nodes.portRangeStart]
        val end = node[Nodes.portRangeEnd]
        val port = (start..end).firstOrNull { it !in usedPorts }
            ?: throw PortExhaustedException("No free ports in range $start-$end on node $targetNodeId")
        PortRegistry.insert {
            it[PortRegistry.nodeId] = targetNodeId
            it[PortRegistry.port] = port
            it[PortRegistry.protocol] = "TCP"
            it[PortRegistry.serverId] = null
        }
        port
    }

    private fun resolveNetworkDnsForMigration(networkId: Uuid?): NetworkDns? {
        if (networkId == null) return null
        return transaction {
            ServerNetworks.selectAll()
                .where { ServerNetworks.id eq networkId }
                .firstOrNull()
                ?.let {
                    val zoneId = it[ServerNetworks.cfZoneId] ?: return@let null
                    val suffix = it[ServerNetworks.cfDomainSuffix] ?: return@let null
                    NetworkDns(zoneId, suffix)
                }
        }
    }

    private data class NetworkDns(val zoneId: String, val domainSuffix: String)

    private fun updateProxyBackendsAfterMigration(serverId: Uuid, targetIp: String, port: Int) {
        // Find proxy servers in the same network that have this server as a backend.
        // Send each proxy a restart-container command so it re-discovers the new node IP.
        val proxyServerIds = transaction {
            ProxyBackends.selectAll()
                .where { ProxyBackends.backendServerId eq serverId }
                .map { it[ProxyBackends.proxyServerId] }
        }
        if (proxyServerIds.isEmpty()) return
        for (proxyServerId in proxyServerIds) {
            val nodeIdStr = transaction {
                Servers.selectAll()
                    .where { Servers.id eq proxyServerId }
                    .firstOrNull()
                    ?.get(Servers.nodeId)
                    ?.toString()
            } ?: continue
            val sent = sendToNode(nodeIdStr, masterMessage {
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

private fun ResultRow.toStepData() = MigrationStepData(
    stepNumber = this[MigrationStepLog.stepNumber],
    description = this[MigrationStepLog.description],
    status = this[MigrationStepLog.status],
    startedAt = this[MigrationStepLog.startedAt]?.toUtcString(),
    completedAt = this[MigrationStepLog.completedAt]?.toUtcString(),
    errorMessage = this[MigrationStepLog.errorMessage],
)
