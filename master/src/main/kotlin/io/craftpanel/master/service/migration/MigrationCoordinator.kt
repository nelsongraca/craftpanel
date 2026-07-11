package io.craftpanel.master.service.migration

import io.craftpanel.master.dns.DnsProvider
import io.craftpanel.master.domain.MigrationStatus
import io.craftpanel.master.domain.MigrationStepStatus
import io.craftpanel.master.service.AgentGateway
import io.craftpanel.master.service.ContainerLifecycle
import io.craftpanel.master.service.MigrationEvent
import io.craftpanel.master.service.PortExhaustedException
import io.craftpanel.master.service.ServerExposure
import io.craftpanel.master.service.repo.MigrationRepository
import io.craftpanel.master.service.repo.NodeRepository
import io.craftpanel.master.service.repo.PortRepository
import io.craftpanel.master.service.repo.ProxyBackendRepository
import io.craftpanel.master.service.repo.ServerRepository
import io.craftpanel.proto.masterMessage
import io.craftpanel.proto.restartContainerCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Owns collaborators (repositories, gateway, DNS, lifecycle) and behavior for a migration run.
 * State that individual steps read/write lives on [MigrationPlan] instead.
 */
open class MigrationCoordinator(
    val migrationRepository: MigrationRepository,
    val serverRepository: ServerRepository,
    val portRepository: PortRepository,
    val proxyBackendRepository: ProxyBackendRepository,
    val nodeRepository: NodeRepository,
    val gateway: AgentGateway,
    val dnsProvider: DnsProvider?,
    val lifecycle: ContainerLifecycle,
    private val serverExposure: ServerExposure,
    val scope: CoroutineScope,
    private val eventFlow: MutableSharedFlow<MigrationEvent>?
) {

    private val clock = Clock.System
    private val log = org.slf4j.LoggerFactory.getLogger("MigrationCoordinator")

    open suspend fun emit(event: MigrationEvent) {
        eventFlow?.emit(event)
    }

    open fun updateStatus(plan: MigrationPlan, status: MigrationStatus) {
        val ts = clock.now()
        migrationRepository.updateMigrationStatus(
            plan.migrationId,
            status,
            if (status == MigrationStatus.COMPLETED || status == MigrationStatus.FAILED) ts else null
        )
        scope.launch { emit(MigrationEvent.Status(status.name)) }
    }

    open fun startStep(plan: MigrationPlan, stepNum: Int, description: String): Uuid {
        val step = migrationRepository.createMigrationStep(plan.migrationId, stepNum, description)
        migrationRepository.updateMigrationStepStatus(step.id, MigrationStepStatus.RUNNING, clock.now(), null, null)
        scope.launch { emit(MigrationEvent.StepStarted(stepNum, description)) }
        return step.id
    }

    open fun completeStep(stepId: Uuid, success: Boolean, error: String? = null) {
        migrationRepository.updateMigrationStepStatus(
            stepId,
            if (success) MigrationStepStatus.SUCCESS else MigrationStepStatus.FAILED,
            null,
            clock.now(),
            error
        )
    }

    open suspend fun failMigration(plan: MigrationPlan, error: String) {
        updateStatus(plan, MigrationStatus.FAILED)
        emit(MigrationEvent.Failed(error))
    }

    open fun restartSource(plan: MigrationPlan) {
        if (plan.sourceStopped) {
            scope.launch {
                runCatching { lifecycle.start(plan.serverRow, needsRecreate = false, nodeId = plan.sourceNodeIdStr) }
            }
        }
    }

    open fun allocateRsyncPort(plan: MigrationPlan): Int {
        val usedPorts = portRepository.findUsedPortsOnNode(plan.targetNodeId)
            .toSet()
        val port = (plan.targetNodeRow.portRangeStart..plan.targetNodeRow.portRangeEnd)
            .firstOrNull { it !in usedPorts }
            ?: throw PortExhaustedException(
                "No free ports in range ${plan.targetNodeRow.portRangeStart}-${plan.targetNodeRow.portRangeEnd} on node ${plan.targetNodeId}"
            )
        portRepository.registerPort(plan.targetNodeId, port, "TCP", null)
        return port
    }

    open fun updateProxyBackendsAfterMigration(serverId: Uuid, targetIp: String, port: Int) {
        val proxyServerIds = proxyBackendRepository.findProxyServersForBackend(serverId)
        if (proxyServerIds.isEmpty()) return
        for (proxyServerId in proxyServerIds) {
            val proxyServer = serverRepository.findById(proxyServerId) ?: continue
            val nodeIdStr = proxyServer.nodeId.toString()
            val sent = gateway.sendToNode(
                nodeIdStr,
                masterMessage {
                    restartContainer = restartContainerCommand { this.serverId = proxyServerId.toString() }
                }
            )
            if (sent) {
                log.info("Triggered proxy restart for server $proxyServerId on node $nodeIdStr after migration of $serverId to $targetIp:$port")
            } else {
                log.warn("Could not reach node $nodeIdStr to restart proxy $proxyServerId after migration of $serverId — manual restart may be required")
            }
        }
    }

    open fun resolveTargetDns(plan: MigrationPlan): ServerExposure.NetworkDns? = serverExposure.resolveNetworkDns(plan.serverRow.networkId)
}
