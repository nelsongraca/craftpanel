package io.craftpanel.master.service.migration

import io.craftpanel.master.domain.MigrationStatus
import io.craftpanel.master.domain.MigrationStepStatus
import io.craftpanel.master.service.AgentGateway
import io.craftpanel.master.service.ContainerLifecycle
import io.craftpanel.master.service.MigrationEvent
import io.craftpanel.master.service.NotFoundException
import io.craftpanel.master.service.PortExhaustedException
import io.craftpanel.master.service.repo.NetworkRepository
import io.craftpanel.master.service.repo.NodeRepository
import io.craftpanel.master.service.repo.NodeRow
import io.craftpanel.master.service.repo.ServerRepository
import io.craftpanel.master.service.repo.ServerRow
import io.craftpanel.master.dns.DnsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.time.Clock
import kotlin.uuid.Uuid

data class MigrationContext(
    val migrationId: Uuid,
    val migrationIdStr: String,
    val serverId: Uuid,
    val serverIdStr: String,
    val sourceNodeId: Uuid,
    val sourceNodeIdStr: String,
    val targetNodeId: Uuid,
    val targetNodeIdStr: String,
    val rsyncImage: String,
    val playerWarningMessage: String,
    val containerNamePrefix: String,
    val serverRow: ServerRow,
    val targetNodeRow: NodeRow,
    val targetPrivateIp: String,
    val gateway: AgentGateway,
    val serverRepository: ServerRepository,
    val nodeRepository: NodeRepository,
    val networkRepository: NetworkRepository,
    val dnsProvider: DnsProvider?,
    val lifecycle: ContainerLifecycle,
    val scope: CoroutineScope,
    val eventFlow: MutableSharedFlow<MigrationEvent>?,
) {

    var rsyncPort: Int = 0
    var rsyncPassword: String = ""
    var sourceStopped: Boolean = false
    var assignedPort: Int = 0
    var freshServerRow: ServerRow? = null

    private val clock = Clock.System
    private val log = org.slf4j.LoggerFactory.getLogger("MigrationContext")

    suspend fun emit(event: MigrationEvent) {
        eventFlow?.emit(event)
    }

    fun updateStatus(status: MigrationStatus) {
        val ts = clock.now()
        serverRepository.updateMigrationStatus(
            migrationId, status,
            if (status == MigrationStatus.COMPLETED || status == MigrationStatus.FAILED) ts else null,
        )
        scope.launch { emit(MigrationEvent.Status(status.name)) }
    }

    fun startStep(stepNum: Int, description: String): Uuid {
        val step = serverRepository.createMigrationStep(migrationId, stepNum, description)
        serverRepository.updateMigrationStepStatus(step.id, MigrationStepStatus.RUNNING, clock.now(), null, null)
        scope.launch { emit(MigrationEvent.StepStarted(stepNum, description)) }
        return step.id
    }

    fun completeStep(stepId: Uuid, success: Boolean, error: String? = null) {
        serverRepository.updateMigrationStepStatus(
            stepId,
            if (success) MigrationStepStatus.SUCCESS else MigrationStepStatus.FAILED,
            null,
            clock.now(),
            error,
        )
    }

    suspend fun failMigration(error: String) {
        updateStatus(MigrationStatus.FAILED)
        emit(MigrationEvent.Failed(error))
    }

    fun restartSource() {
        if (sourceStopped) scope.launch {
            runCatching { lifecycle.start(serverRow, needsRecreate = false, nodeId = sourceNodeIdStr) }
        }
    }

    fun allocateRsyncPort(): Int {
        val usedPorts = serverRepository.findUsedPortsOnNode(targetNodeId)
            .toSet()
        val port = (targetNodeRow.portRangeStart..targetNodeRow.portRangeEnd)
            .firstOrNull { it !in usedPorts }
            ?: throw PortExhaustedException(
                "No free ports in range ${targetNodeRow.portRangeStart}-${targetNodeRow.portRangeEnd} on node $targetNodeId",
            )
        serverRepository.registerPort(targetNodeId, port, "TCP", null)
        return port
    }

    fun resolveNetworkDnsForMigration(networkId: Uuid?): NetworkDns? {
        if (networkId == null) return null
        return networkRepository.findById(networkId)
            ?.let {
                val zoneId = it.cfZoneId ?: return null
                val suffix = it.cfDomainSuffix ?: return null
                NetworkDns(zoneId, suffix)
            }
    }

    fun updateProxyBackendsAfterMigration(serverId: Uuid, targetIp: String, port: Int) {
        val proxyServerIds = serverRepository.findProxyServersForBackend(serverId)
        if (proxyServerIds.isEmpty()) return
        for (proxyServerId in proxyServerIds) {
            val proxyServer = serverRepository.findById(proxyServerId) ?: continue
            val nodeIdStr = proxyServer.nodeId.toString()
            val sent = gateway.sendToNode(nodeIdStr, io.craftpanel.proto.masterMessage {
                restartContainer = io.craftpanel.proto.restartContainerCommand { this.serverId = proxyServerId.toString() }
            })
            if (sent) {
                log.info("Triggered proxy restart for server $proxyServerId on node $nodeIdStr after migration of $serverId to $targetIp:$port")
            }
            else {
                log.warn("Could not reach node $nodeIdStr to restart proxy $proxyServerId after migration of $serverId — manual restart may be required")
            }
        }
    }

    data class NetworkDns(val zoneId: String, val domainSuffix: String)
}
