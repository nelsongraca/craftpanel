package io.craftpanel.master.service

import io.craftpanel.master.*
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.service.repo.ServerRow
import io.craftpanel.master.util.toUtcString
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class ContainerLifecycleTest :
    FunSpec({
        val repos = TestRepositories()
        val events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 16)
        lateinit var nodeId: Uuid
        lateinit var serverId: Uuid

        lateinit var gateway: TestAgentGateway

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
            gateway = TestAgentGateway(agentEvents = events)
            val resolvedNodeId = transaction {
                Nodes.insert {
                    it[Nodes.hostname] = "test-node"
                    it[Nodes.displayName] = "test-node"
                    it[Nodes.publicIp] = "1.2.3.4"
                    it[Nodes.privateIp] = "10.0.0.1"
                    it[Nodes.tokenHash] = "a".repeat(64)
                    it[Nodes.status] = "ACTIVE"
                    it[Nodes.totalRamMb] = 8192
                    it[Nodes.totalCpuShares] = 0
                    it[Nodes.portRangeStart] = 25565
                    it[Nodes.portRangeEnd] = 25600
                }[Nodes.id].let { Uuid.parse(it.toString()) }
            }
            nodeId = resolvedNodeId
            serverId = transaction {
                Servers.insert {
                    it[Servers.nodeId] = resolvedNodeId
                    it[Servers.name] = "test-server"
                    it[Servers.displayName] = "test-server"
                    it[Servers.serverType] = "VANILLA"
                    it[Servers.mcVersion] = "1.21.4"
                    it[Servers.itzgImageTag] = "latest"
                    it[Servers.hostPort] = 25565
                    it[Servers.memoryMb] = 1024
                    it[Servers.cpuShares] = 0
                    it[Servers.status] = "STOPPED"
                }[Servers.id].let { Uuid.parse(it.toString()) }
            }
        }

        fun serverRow(id: Uuid = serverId): ServerRow = transaction {
            Servers.selectAll()
                .where { Servers.id eq id }
                .first()
                .let { r ->
                    ServerRow(
                        id = r[Servers.id],
                        name = r[Servers.name],
                        displayName = r[Servers.displayName],
                        description = r[Servers.description],
                        nodeId = r[Servers.nodeId],
                        networkId = r[Servers.networkId],
                        serverType = r[Servers.serverType],
                        mcVersion = r[Servers.mcVersion],
                        status = r[Servers.status],
                        hostPort = r[Servers.hostPort],
                        memoryMb = r[Servers.memoryMb],
                        cpuShares = r[Servers.cpuShares],
                        exposedExternally = r[Servers.exposedExternally],
                        publicSubdomain = r[Servers.publicSubdomain],
                        dnsRecordId = r[Servers.dnsRecordId],
                        dnsRecordName = r[Servers.dnsRecordName],
                        customHostname = r[Servers.customHostname],
                        configMode = r[Servers.configMode],
                        stopCommand = r[Servers.stopCommand],
                        itzgImageTag = r[Servers.itzgImageTag],
                        needsRecreate = r[Servers.needsRecreate],
                        backupSchedule = r[Servers.backupSchedule],
                        backupMaxCount = r[Servers.backupMaxCount],
                        backupScheduleLastFired = r[Servers.backupScheduleLastFired]?.toString(),
                        lastPlayerCount = r[Servers.lastPlayerCount],
                        lastPlayerNames = r[Servers.lastPlayerNames],
                        lastPlayerUpdate = r[Servers.lastPlayerUpdate]?.toString(),
                        lastSeenAt = r[Servers.lastSeenAt]?.toString(),
                        createdAt = r[Servers.createdAt].toUtcString(),
                        updatedAt = r[Servers.updatedAt].toString()
                    )
                }
        }

        fun lifecycle(startTimeout: kotlin.time.Duration = 2.seconds, stopTimeout: kotlin.time.Duration = 2.seconds, removeTimeout: kotlin.time.Duration = 2.seconds) = ContainerLifecycle(
            gateway = gateway,
            modService = ModService(modRepository = repos.modRepository, serverRepository = repos.serverRepository),
            serverRepository = repos.serverRepository,
            envVarsRepository = repos.envVarsRepository,
            startTimeout = startTimeout,
            stopTimeout = stopTimeout,
            removeTimeout = removeTimeout
        )

        test("start - needsRecreate false - sends single StartContainerCommand") {
            val server = serverRow()
            val lc = lifecycle()
            launch {
                delay(50.milliseconds)
                events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.HEALTHY))
            }
            lc.start(server, needsRecreate = false)
            gateway.sent.size shouldBe 1
            gateway.sent[0].second.hasStartContainer() shouldBe true
            val cmd = gateway.sent[0].second.startContainer
            cmd.needsRecreate shouldBe false
            cmd.containerName shouldBe "craftpanel-$serverId"
            cmd.image shouldBe "itzg/minecraft-server:latest"
            cmd.envVarsMap["EULA"] shouldBe "TRUE"
            cmd.dataContainerPath shouldBe "/data"
        }

        test("start - proxy server type - data container path is /server") {
            val proxyId = transaction {
                Servers.insert {
                    it[Servers.nodeId] = nodeId
                    it[Servers.name] = "test-proxy"
                    it[Servers.displayName] = "test-proxy"
                    it[Servers.serverType] = "VELOCITY"
                    it[Servers.mcVersion] = "latest"
                    it[Servers.itzgImageTag] = "latest"
                    it[Servers.hostPort] = 25566
                    it[Servers.memoryMb] = 1024
                    it[Servers.cpuShares] = 0
                    it[Servers.status] = "STOPPED"
                }[Servers.id].let { Uuid.parse(it.toString()) }
            }
            val server = serverRow(proxyId)
            val cmd = lifecycle().buildStartMessage(server, needsRecreate = false).startContainer
            cmd.image shouldBe "itzg/mc-proxy:latest"
            cmd.dataContainerPath shouldBe "/server"
        }

        test("start - needsRecreate true - sends StartContainerCommand with needsRecreate=true") {
            val server = serverRow()
            val lc = lifecycle()
            launch {
                delay(50.milliseconds)
                events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.HEALTHY))
            }
            lc.start(server, needsRecreate = true)
            gateway.sent.size shouldBe 1
            gateway.sent[0].second.hasStartContainer() shouldBe true
            val cmd = gateway.sent[0].second.startContainer
            cmd.needsRecreate shouldBe true
        }

        test("start - UNHEALTHY response - throws ContainerLifecycleException") {
            val server = serverRow()
            val lc = lifecycle()
            launch {
                delay(50.milliseconds)
                events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.UNHEALTHY))
            }
            shouldThrow<ContainerLifecycleException> {
                lc.start(server, needsRecreate = false)
            }
        }

        test("start - timeout - throws ContainerLifecycleException") {
            val server = serverRow()
            val lc = lifecycle(startTimeout = 100.milliseconds)
            shouldThrow<ContainerLifecycleException> {
                lc.start(server, needsRecreate = false)
            }
        }

        test("stop - agent not connected - throws BadGatewayException") {
            val server = serverRow()
            val lc = ContainerLifecycle(
                gateway = TestAgentGateway(agentEvents = events, sendResult = false),
                modService = ModService(modRepository = repos.modRepository, serverRepository = repos.serverRepository),
                serverRepository = repos.serverRepository,
                envVarsRepository = repos.envVarsRepository
            )
            shouldThrow<BadGatewayException> {
                lc.stop(server, nodeId.toString())
            }
        }
    })
