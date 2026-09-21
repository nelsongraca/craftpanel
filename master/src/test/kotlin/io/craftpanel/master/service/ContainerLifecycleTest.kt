package io.craftpanel.master.service

import io.craftpanel.master.*
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.ServerEnvVars
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.domain.DesiredStatus
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.service.repo.ServerView
import io.craftpanel.master.util.toUtcString
import io.craftpanel.proto.ServerDesiredState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
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
                    it[Nodes.totalCpuMillicores] = 0
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
                    it[Servers.cpuLimitMillicores] = 0
                    it[Servers.status] = "STOPPED"
                }[Servers.id].let { Uuid.parse(it.toString()) }
            }
        }

        fun serverRow(id: Uuid = serverId): ServerView = transaction {
            Servers.selectAll()
                .where { Servers.id eq id }
                .first()
                .let { r ->
                    ServerView(
                        id = r[Servers.id].value,
                        name = r[Servers.name],
                        displayName = r[Servers.displayName],
                        description = r[Servers.description],
                        nodeId = r[Servers.nodeId].value,
                        networkId = r[Servers.networkId]?.value,
                        serverType = ServerType.fromDb(r[Servers.serverType]),
                        mcVersion = r[Servers.mcVersion],
                        status = r[Servers.status],
                        hostPort = r[Servers.hostPort],
                        memoryMb = r[Servers.memoryMb],
                        cpuLimitMillicores = r[Servers.cpuLimitMillicores],
                        exposedExternally = r[Servers.exposedExternally],
                        publicSubdomain = r[Servers.publicSubdomain],
                        dnsRecordId = r[Servers.dnsRecordId],
                        dnsRecordName = r[Servers.dnsRecordName],
                        customHostname = r[Servers.customHostname],
                        configMode = r[Servers.configMode],
                        stopCommand = r[Servers.stopCommand],
                        itzgImageTag = r[Servers.itzgImageTag],
                        desiredStatus = r[Servers.desiredStatus],
                        backupSchedule = r[Servers.backupSchedule],
                        backupMaxCount = r[Servers.backupMaxCount],
                        backupScheduleLastFired = r[Servers.backupScheduleLastFired]?.toString(),
                        customServerJar = r[Servers.customServerJar],
                        containerListenPort = r[Servers.containerListenPort],
                        containerProtocol = r[Servers.containerProtocol],
                        disableHealthcheck = r[Servers.disableHealthcheck],
                        forceRedownload = r[Servers.forceRedownload],
                        dataDirName = r[Servers.dataDirName],
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
            serverIntent = ServerIntent(repos.serverRepository),
            envVarsRepository = repos.envVarsRepository,
            startTimeout = startTimeout,
            stopTimeout = stopTimeout,
            removeTimeout = removeTimeout
        )

        // ── sendDesiredState ───────────────────────────────────────────────────

        test("sendDesiredState - sends ServerDesiredState envelope") {
            val server = serverRow()
            val ok = lifecycle().sendDesiredState(server, DesiredStatus.RUNNING)
            ok shouldBe true
            gateway.sent.size shouldBe 1
            val msg = gateway.sent[0].second
            msg.hasServerDesiredState() shouldBe true
            msg.serverDesiredState.desired shouldBe ServerDesiredState.Desired.RUNNING
            msg.serverDesiredState.serverId shouldBe serverId.toString()
        }

        test("sendDesiredState - agent not connected - returns false") {
            val server = serverRow()
            val lc = ContainerLifecycle(
                gateway = TestAgentGateway(agentEvents = events, sendResult = false),
                modService = ModService(modRepository = repos.modRepository, serverRepository = repos.serverRepository),
                serverIntent = ServerIntent(repos.serverRepository),
                envVarsRepository = repos.envVarsRepository
            )
            val ok = lc.sendDesiredState(server, DesiredStatus.RUNNING)
            ok shouldBe false
        }

        test("sendRemove - sends RemoveContainerCommand") {
            val server = serverRow()
            val ok = lifecycle().sendRemove(server, nodeId.toString())
            ok shouldBe true
            gateway.sent.size shouldBe 1
            gateway.sent[0].second.hasRemoveContainer() shouldBe true
        }

        // ── buildStartSpec ─────────────────────────────────────────────────────

        test("buildStartSpec - vanilla server - correct fields") {
            val server = serverRow()
            val cmd = lifecycle().buildStartSpec(server)
            cmd.serverId shouldBe serverId.toString()
            cmd.containerName shouldBe "craftpanel-$serverId"
            cmd.image shouldBe "itzg/minecraft-server:latest"
            cmd.envVarsMap["EULA"] shouldBe "TRUE"
            cmd.envVarsMap["SERVER_PORT"] shouldBe "25565"
            cmd.internalListenPort shouldBe 25565
            cmd.dataContainerPath shouldBe "/data"
            cmd.dataDirName shouldBe ""
        }

        test("buildStartSpec - carries the data-dir override") {
            transaction {
                Servers.update({ Servers.id eq serverId }) { it[Servers.dataDirName] = "survival" }
            }
            val cmd = lifecycle().buildStartSpec(serverRow())
            cmd.dataDirName shouldBe "survival"
        }

        test("buildStartSpec - proxy server type - data container path is /server") {
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
                    it[Servers.cpuLimitMillicores] = 0
                    it[Servers.status] = "STOPPED"
                }[Servers.id].let { Uuid.parse(it.toString()) }
            }
            val server = serverRow(proxyId)
            val cmd = lifecycle().buildStartSpec(server)
            cmd.image shouldBe "itzg/mc-proxy:latest"
            cmd.dataContainerPath shouldBe "/server"
            cmd.internalListenPort shouldBe 25577
            cmd.envVarsMap["SERVER_PORT"] shouldBe "25577"
            cmd.containerUser shouldBe ""
        }

        test("buildStartSpec - PICOLIMBO server type - sets containerUser to 1000:1000") {
            val picolimboId = transaction {
                Servers.insert {
                    it[Servers.nodeId] = nodeId
                    it[Servers.name] = "test-picolimbo"
                    it[Servers.displayName] = "test-picolimbo"
                    it[Servers.serverType] = "PICOLIMBO"
                    it[Servers.mcVersion] = "1.21.4"
                    it[Servers.itzgImageTag] = "latest"
                    it[Servers.hostPort] = 25567
                    it[Servers.memoryMb] = 512
                    it[Servers.cpuLimitMillicores] = 0
                    it[Servers.status] = "STOPPED"
                }[Servers.id].let { Uuid.parse(it.toString()) }
            }
            val server = serverRow(picolimboId)
            val cmd = lifecycle().buildStartSpec(server)
            cmd.image shouldBe "ghcr.io/quozul/picolimbo:latest"
            cmd.dataContainerPath shouldBe "/usr/src/app"
            cmd.containerUser shouldBe "1000:1000"
        }

        test("buildStartSpec - CUSTOM server type - injects CUSTOM_SERVER and forces VERSION=LATEST") {
            val customId = transaction {
                Servers.insert {
                    it[Servers.nodeId] = nodeId
                    it[Servers.name] = "test-custom"
                    it[Servers.displayName] = "test-custom"
                    it[Servers.serverType] = "CUSTOM"
                    it[Servers.mcVersion] = "1.21.4"
                    it[Servers.itzgImageTag] = "latest"
                    it[Servers.hostPort] = 25570
                    it[Servers.memoryMb] = 1024
                    it[Servers.cpuLimitMillicores] = 0
                    it[Servers.status] = "STOPPED"
                    it[Servers.customServerJar] = "/data/my-server.jar"
                    it[Servers.configMode] = "MANUAL"
                }[Servers.id].let { Uuid.parse(it.toString()) }
            }
            val row = serverRow(customId).copy(
                containerProtocol = "UDP",
                containerListenPort = 25566,
                disableHealthcheck = true,
                forceRedownload = true
            )
            val cmd = lifecycle().buildStartSpec(row)
            cmd.image shouldBe "itzg/minecraft-server:latest"
            cmd.dataContainerPath shouldBe "/data"
            cmd.internalListenPort shouldBe 25566
            cmd.containerProtocol shouldBe "UDP"
            cmd.envVarsMap["TYPE"] shouldBe "CUSTOM"
            cmd.envVarsMap["VERSION"] shouldBe "LATEST"
            cmd.envVarsMap["SERVER_PORT"] shouldBe "25566"
            cmd.envVarsMap["CUSTOM_SERVER"] shouldBe "/data/my-server.jar"
            cmd.envVarsMap["DISABLE_HEALTHCHECK"] shouldBe "true"
            cmd.envVarsMap["FORCE_REDOWNLOAD"] shouldBe "true"
            cmd.envVarsMap["OVERRIDE_SERVER_PROPERTIES"] shouldBe "false"
        }

        test("buildStartSpec - MANUAL config mode - injects OVERRIDE_SERVER_PROPERTIES=false and strips JVM vars") {
            transaction {
                Servers.update({ Servers.id eq serverId }) {
                    it[Servers.configMode] = "MANUAL"
                }
                ServerEnvVars.insert {
                    it[ServerEnvVars.serverId] = serverId
                    it[ServerEnvVars.key] = "USE_AIKAR_FLAGS"
                    it[ServerEnvVars.value] = "true"
                }
                ServerEnvVars.insert {
                    it[ServerEnvVars.serverId] = EntityID(serverId, Servers)
                    it[ServerEnvVars.key] = "USE_MEOWICE_FLAGS"
                    it[ServerEnvVars.value] = "true"
                }
                ServerEnvVars.insert {
                    it[ServerEnvVars.serverId] = EntityID(serverId, Servers)
                    it[ServerEnvVars.key] = "JVM_OPTS"
                    it[ServerEnvVars.value] = "-Xmx4G"
                }
                ServerEnvVars.insert {
                    it[ServerEnvVars.serverId] = EntityID(serverId, Servers)
                    it[ServerEnvVars.key] = "JVM_XX_OPTS"
                    it[ServerEnvVars.value] = "-XX:+UseG1GC"
                }
                ServerEnvVars.insert {
                    it[ServerEnvVars.serverId] = EntityID(serverId, Servers)
                    it[ServerEnvVars.key] = "DIFFICULTY"
                    it[ServerEnvVars.value] = "hard"
                }
            }
            val server = serverRow()
            val cmd = lifecycle().buildStartSpec(server)
            cmd.envVarsMap["OVERRIDE_SERVER_PROPERTIES"] shouldBe "false"
            cmd.envVarsMap.containsKey("USE_AIKAR_FLAGS") shouldBe false
            cmd.envVarsMap.containsKey("USE_MEOWICE_FLAGS") shouldBe false
            cmd.envVarsMap.containsKey("JVM_OPTS") shouldBe false
            cmd.envVarsMap.containsKey("JVM_XX_OPTS") shouldBe false
            cmd.envVarsMap["DIFFICULTY"] shouldBe "hard"
            cmd.envVarsMap["MEMORY"] shouldNotBe null
        }

        test("buildStartSpec - MANAGED config mode - no OVERRIDE_SERVER_PROPERTIES") {
            transaction {
                ServerEnvVars.insert {
                    it[ServerEnvVars.serverId] = EntityID(serverId, Servers)
                    it[ServerEnvVars.key] = "USE_AIKAR_FLAGS"
                    it[ServerEnvVars.value] = "true"
                }
            }
            val server = serverRow()
            val cmd = lifecycle().buildStartSpec(server)
            cmd.envVarsMap.containsKey("OVERRIDE_SERVER_PROPERTIES") shouldBe false
            cmd.envVarsMap["USE_AIKAR_FLAGS"] shouldBe "true"
        }

        // ── await-based start/stop/remove ──────────────────────────────────────

        test("start - waits for HEALTHY and sets desired_status") {
            val server = serverRow()
            val lc = lifecycle()
            launch {
                delay(50.milliseconds)
                events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.HEALTHY))
            }
            lc.start(server)
            val updated = repos.serverRepository.findById(serverId)!!
            DesiredStatus.fromDb(updated.desiredStatus) shouldBe DesiredStatus.RUNNING
        }

        test("start - timeout - throws ContainerLifecycleException") {
            val server = serverRow()
            val lc = lifecycle(startTimeout = 100.milliseconds)
            shouldThrow<ContainerLifecycleException> {
                lc.start(server)
            }
        }

        test("stop - waits for STOPPED and sets desired_status") {
            val server = serverRow()
            val lc = lifecycle()
            launch {
                delay(50.milliseconds)
                events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.STOPPED))
            }
            lc.stop(server, nodeId.toString())
            val updated = repos.serverRepository.findById(serverId)!!
            DesiredStatus.fromDb(updated.desiredStatus) shouldBe DesiredStatus.STOPPED
        }

        test("stop - agent not connected - throws BadGatewayException") {
            val server = serverRow()
            val lc = ContainerLifecycle(
                gateway = TestAgentGateway(agentEvents = events, sendResult = false),
                modService = ModService(modRepository = repos.modRepository, serverRepository = repos.serverRepository),
                serverIntent = ServerIntent(repos.serverRepository),
                envVarsRepository = repos.envVarsRepository
            )
            shouldThrow<BadGatewayException> {
                lc.stop(server, nodeId.toString())
            }
        }

        test("remove - waits for STOPPED before sending remove command") {
            val server = serverRow()
            val lc = lifecycle()
            launch {
                delay(50.milliseconds)
                events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.STOPPED))
            }
            lc.remove(server, nodeId.toString())
            gateway.sent.size shouldBe 1
            gateway.sent[0].second.hasRemoveContainer() shouldBe true
        }

        // ── defaultHeapMb (MEMORY heuristic) ──────────────────────────────────

        test("defaultHeapMb reserves a 512MB base plus 12.5% for non-heap") {
            defaultHeapMb(2048) shouldBe 1280
            defaultHeapMb(4096) shouldBe 3072
            defaultHeapMb(8192) shouldBe 6656
        }

        test("defaultHeapMb floors the heap at half the container for tiny servers") {
            defaultHeapMb(512) shouldBe 256
            defaultHeapMb(1024) shouldBe 512
        }

        test("defaultHeapMb is non-positive safe") {
            defaultHeapMb(0) shouldBe 0
            defaultHeapMb(-1) shouldBe 0
        }
    })
