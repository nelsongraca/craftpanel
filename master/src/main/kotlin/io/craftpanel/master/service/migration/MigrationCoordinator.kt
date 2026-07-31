package io.craftpanel.master.service.migration

import io.craftpanel.master.database.entity.MigrationStep
import io.craftpanel.master.database.entity.ServerMigration
import io.craftpanel.master.database.schema.*
import io.craftpanel.master.dns.DnsProvider
import io.craftpanel.master.domain.MigrationStatus
import io.craftpanel.master.domain.MigrationStepStatus
import io.craftpanel.master.service.*
import io.craftpanel.master.service.repo.*
import io.craftpanel.proto.masterMessage
import io.craftpanel.proto.restartContainerCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
        transaction {
            ServerMigration.findById(plan.migrationId)?.let {
                it.status = status.name
                if (status == MigrationStatus.COMPLETED || status == MigrationStatus.FAILED) {
                    it.completedAt = ts.toLocalDateTime(TimeZone.UTC)
                }
            }
        }
        scope.launch { emit(MigrationEvent.Status(status.name)) }
    }

    open fun startStep(plan: MigrationPlan, stepNum: Int, description: String): Uuid {
        val step = transaction {
            MigrationStep.new {
                this.migrationId = EntityID(plan.migrationId, ServerMigrations)
                this.stepNumber = stepNum
                this.description = description
                this.status = MigrationStepStatus.PENDING.name
            }.toMigrationStepRow()
        }
        transaction {
            MigrationStep.findById(step.id)?.let {
                it.status = MigrationStepStatus.RUNNING.name
                it.startedAt = clock.now().toLocalDateTime(TimeZone.UTC)
            }
        }
        scope.launch { emit(MigrationEvent.StepStarted(stepNum, description)) }
        return step.id
    }

    open fun completeStep(stepId: Uuid, success: Boolean, error: String? = null) {
        transaction {
            MigrationStep.findById(stepId)?.let {
                it.status = if (success) MigrationStepStatus.SUCCESS.name else MigrationStepStatus.FAILED.name
                it.completedAt = clock.now().toLocalDateTime(TimeZone.UTC)
                if (error != null) it.errorMessage = error
            }
        }
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
        transaction { PortRegistry.insert { it[PortRegistry.nodeId] = EntityID(plan.targetNodeId, Nodes); it[PortRegistry.port] = port; it[PortRegistry.protocol] = "TCP" } }
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
