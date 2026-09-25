package io.craftpanel.master.service

import io.craftpanel.master.service.repo.NodeRepository
import io.craftpanel.proto.AgentRuntimeSettings
import io.craftpanel.proto.MasterMessage
import io.craftpanel.proto.agentRuntimeSettings
import io.craftpanel.proto.agentRuntimeSettingsUpdate
import io.craftpanel.proto.masterMessage
import io.craftpanel.proto.restartBudget
import org.slf4j.LoggerFactory

/**
 * The single source of install-wide agent runtime tuning and the one place that pushes it.
 *
 * [snapshot] reads the same `system_settings` rows that [ContainerLifecycle] uses to populate the
 * per-server envelope mirror, so the two delivery channels can never disagree. [pushAll] is invoked
 * when an operator changes a backing setting, so connected agents apply the change live instead of
 * waiting for the next reconnect.
 */
class AgentRuntimeSettingsService(
    private val settingsProvider: SettingsProvider,
    private val nodeRepository: NodeRepository,
    private val gateway: AgentGateway
) {

    private val log = LoggerFactory.getLogger(AgentRuntimeSettingsService::class.java)

    fun snapshot(): AgentRuntimeSettings {
        val s = settingsProvider.current()
        return agentRuntimeSettings {
            metricsPollIntervalSeconds = s.metricsPollIntervalSeconds
            metricsCollectionConcurrency = s.metricsCollectionConcurrency
            reconcileIntervalSeconds = s.agentReconcileIntervalSeconds
            restartBudget = restartBudget {
                maxAttempts = s.restartMaxAttempts
                windowSeconds = s.restartWindowSeconds
            }
            jvmMetricsPollIntervalSeconds = s.jvmMetricsPollIntervalSeconds
        }
    }

    /** Pushes the snapshot to one node; false (offline) is a debug-level event, not an error. */
    fun pushToNode(nodeId: String) {
        val pushed = gateway.sendToNode(nodeId, message())
        if (!pushed) log.debug("Node $nodeId offline — runtime settings will arrive on reconnect")
    }

    /** Pushes the snapshot to every node. Offline nodes are skipped. */
    fun pushAll() {
        for (node in nodeRepository.listAll()) {
            pushToNode(node.id.toString())
        }
    }

    private fun message(): MasterMessage = masterMessage {
        agentRuntimeSettings = agentRuntimeSettingsUpdate {
            settings = snapshot()
        }
    }
}
