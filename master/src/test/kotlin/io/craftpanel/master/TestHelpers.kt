package io.craftpanel.master

import io.craftpanel.master.config.NodeConfig
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.grpc.ControlServiceImpl
import io.craftpanel.master.grpc.DataOpContext
import io.craftpanel.master.grpc.handlers.*
import io.craftpanel.master.service.NodeStateReconciler
import io.craftpanel.master.service.repo.NodeRepository
import io.craftpanel.master.service.repo.NodeRepositoryImpl
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.ConcurrentHashMap

fun createTestControlServiceImpl(
    nodeConfig: NodeConfig = NodeConfig("test-token", 50052),
    nodeStateReconciler: NodeStateReconciler,
    nodeRepository: NodeRepository = NodeRepositoryImpl()
): ControlServiceImpl {
    val agentEvents = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 1024)
    val dataOpContext = DataOpContext(ConcurrentHashMap(), ConcurrentHashMap())
    val nodeStateHandler = NodeStateHandler(agentEvents, nodeStateReconciler)
    val nodeMetricsHandler = NodeMetricsHandler(agentEvents, nodeStateReconciler)
    val containerMetricsHandler = ContainerMetricsHandler(agentEvents)
    val serverStatusHandler = ServerStatusHandler(agentEvents)
    val playerUpdateHandler = PlayerUpdateHandler(agentEvents)
    val backupHandler = BackupHandler(agentEvents)
    val migrationHandler = MigrationHandler(agentEvents)
    val dataOpResponseHandler = DataOpResponseHandler(dataOpContext)
    return ControlServiceImpl(
        nodeConfig = nodeConfig,
        nodeStateReconciler = nodeStateReconciler,
        nodeRepository = nodeRepository,
        agentEventsFlow = agentEvents,
        dataOpContext = dataOpContext,
        nodeStateHandler = nodeStateHandler,
        nodeMetricsHandler = nodeMetricsHandler,
        containerMetricsHandler = containerMetricsHandler,
        serverStatusHandler = serverStatusHandler,
        playerUpdateHandler = playerUpdateHandler,
        backupHandler = backupHandler,
        migrationHandler = migrationHandler,
        dataOpResponseHandler = dataOpResponseHandler
    )
}
