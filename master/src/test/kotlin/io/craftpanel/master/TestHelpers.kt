package io.craftpanel.master

import io.craftpanel.master.TestAgentGateway
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.config.NodeConfig
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.grpc.AgentDataOps
import io.craftpanel.master.grpc.ControlServiceImpl
import io.craftpanel.master.grpc.DataOpContext
import io.craftpanel.master.grpc.NodeRegistrar
import io.craftpanel.master.grpc.handlers.*
import io.craftpanel.master.service.AgentGateway
import io.craftpanel.master.service.NodeStateReconciler
import io.craftpanel.master.service.repo.NodeRepository
import io.craftpanel.master.service.repo.impl.NodeRepositoryImpl
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.ConcurrentHashMap

fun createTestNodeRegistrar(nodeConfig: NodeConfig = NodeConfig("test-token", 50052), nodeRepository: NodeRepository = NodeRepositoryImpl()): NodeRegistrar = NodeRegistrar(nodeConfig, nodeRepository)

fun createTestAgentDataOps(
    dataOpContext: DataOpContext = DataOpContext(ConcurrentHashMap(), ConcurrentHashMap()),
    sendToNode: (String, io.craftpanel.proto.MasterMessage) -> Boolean = { _, _ -> false }
): AgentDataOps = AgentDataOps(dataOpContext, sendToNode)

fun createTestControlServiceImpl(
    nodeConfig: NodeConfig = NodeConfig("test-token", 50052),
    nodeStateReconciler: NodeStateReconciler,
    nodeRepository: NodeRepository = NodeRepositoryImpl(),
    agentGateway: AgentGateway = TestAgentGateway(),
    repos: TestRepositories = TestRepositories()
): ControlServiceImpl {
    val agentEvents = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 1024)
    val dataOpContext = DataOpContext(ConcurrentHashMap(), ConcurrentHashMap())
    val nodeStateHandler = NodeStateHandler(
        agentEvents,
        nodeStateReconciler
    )
    val nodeMetricsHandler = NodeMetricsHandler(agentEvents, nodeStateReconciler)
    val containerMetricsHandler = ContainerMetricsHandler(agentEvents)
    val serverStatusHandler = ServerStatusHandler(agentEvents)
    val playerUpdateHandler = PlayerUpdateHandler(agentEvents)
    val backupHandler = BackupHandler(agentEvents)
    val migrationHandler = MigrationHandler(agentEvents)
    val dataOpResponseHandler = DataOpResponseHandler(dataOpContext)
    return ControlServiceImpl(
        nodeStateReconciler = nodeStateReconciler,
        nodeRegistrar = createTestNodeRegistrar(nodeConfig, nodeRepository),
        agentEventsFlow = agentEvents,
        dataOpContext = dataOpContext,
        nodeStateHandler = nodeStateHandler,
        nodeMetricsHandler = nodeMetricsHandler,
        containerMetricsHandler = containerMetricsHandler,
        serverStatusHandler = serverStatusHandler,
        playerUpdateHandler = playerUpdateHandler,
        backupHandler = backupHandler,
        migrationHandler = migrationHandler,
        dataOpResponseHandler = dataOpResponseHandler,
        serverRepository = repos.serverRepository,
        backupRepository = repos.backupRepository
    )
}
