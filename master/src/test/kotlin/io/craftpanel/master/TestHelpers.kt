package io.craftpanel.master

import io.craftpanel.master.TestAgentGateway
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.config.NodeConfig
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.grpc.AgentDataOps
import io.craftpanel.master.grpc.AgentRegistry
import io.craftpanel.master.grpc.ControlServiceImpl
import io.craftpanel.master.grpc.DataOpContext
import io.craftpanel.master.grpc.handlers.*
import io.craftpanel.master.service.AgentGateway
import io.craftpanel.master.service.NodeRegistrationService
import io.craftpanel.master.service.NodeStateReconciler
import io.craftpanel.master.service.PortAllocator
import io.craftpanel.master.service.repo.NodeRepository
import io.craftpanel.master.service.repo.PortRepository
import io.craftpanel.master.service.repo.impl.NodeRepositoryImpl
import io.craftpanel.master.service.repo.impl.PortRepositoryImpl
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.ConcurrentHashMap

fun createTestPortAllocator(
    portRepository: PortRepository = PortRepositoryImpl(),
    nodeRepository: NodeRepository = NodeRepositoryImpl()
): PortAllocator = PortAllocator(nodeRepository, portRepository)

fun createTestNodeRegistrationService(nodeConfig: NodeConfig = NodeConfig("test-token", 50052), nodeRepository: NodeRepository = NodeRepositoryImpl()): NodeRegistrationService = NodeRegistrationService(nodeConfig, nodeRepository)

fun createTestAgentDataOps(
    dataOpContext: DataOpContext = DataOpContext(ConcurrentHashMap(), ConcurrentHashMap()),
    sendToNode: (String, io.craftpanel.proto.MasterMessage) -> Boolean = { _, _ -> false },
    sendToNodeSuspending: suspend (String, io.craftpanel.proto.MasterMessage) -> Boolean = { nodeId, msg -> sendToNode(nodeId, msg) }
): AgentDataOps = AgentDataOps(dataOpContext, sendToNode, sendToNodeSuspending)

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
        nodeStateReconciler,
        pushDesiredStates = {}
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
        nodeRegistrationService = createTestNodeRegistrationService(nodeConfig, nodeRepository),
        registry = AgentRegistry(agentEvents, repos.serverRepository, repos.backupRepository),
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
