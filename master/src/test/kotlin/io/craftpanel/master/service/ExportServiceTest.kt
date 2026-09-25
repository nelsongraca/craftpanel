package io.craftpanel.master.service

import io.craftpanel.master.*
import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.database.entity.Server
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.ServerEnvVars
import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class ExportServiceTest :
    FunSpec({
        val repos = TestRepositories()
        val serverRepository: ServerRepository = repos.serverRepository
        val networkRepository = NetworkRepositoryImpl()
        val nodeRepository = NodeRepositoryImpl()
        val settingsRepository = SettingsRepositoryImpl()

        val networkService = NetworkService(
            networkRepository = networkRepository,
            serverRepository = serverRepository,
            nodeRepository = nodeRepository,
            userRepository = UserRepositoryImpl(),
            groupRepository = GroupRepositoryImpl()
        )

        val provisioning = ServerProvisioning(
            serverRepository = serverRepository,
            nodeRepository = nodeRepository,
            networkRepository = networkRepository,
            settingsProvider = SettingsProvider(settingsRepository),
            portAllocator = createTestPortAllocator(repos.portRepository),
            extraPortRepository = repos.extraPortRepository,
            envVarsRepository = repos.envVarsRepository,
            modRepository = repos.modRepository,
            networkService = networkService,
            serverHostnames = ServerHostnames(SettingsProvider(settingsRepository), serverRepository)
        )

        fun createServer(
            name: String,
            displayName: String? = null,
            description: String? = null,
            nodeId: String,
            networkId: String? = null,
            serverType: String,
            mcVersion: String = "LATEST",
            itzgImageTag: String = "latest",
            memoryMb: Int,
            cpuLimitMillicores: Int = 0,
            expiresAt: String? = null,
            customServerJar: String? = null,
            containerListenPort: Int? = null,
            containerProtocol: String? = null,
            disableHealthcheck: Boolean? = null,
            forceRedownload: Boolean? = null
        ): ServerView = provisioning.provision(
            ServerProvisionSpec(
                name = name,
                displayName = displayName,
                description = description,
                nodeId = nodeId,
                networkId = networkId,
                serverType = serverType,
                mcVersion = mcVersion,
                itzgImageTag = itzgImageTag,
                memoryMb = memoryMb,
                cpuLimitMillicores = cpuLimitMillicores,
                expiresAt = expiresAt,
                customServerJar = customServerJar,
                containerListenPort = containerListenPort,
                containerProtocol = containerProtocol,
                disableHealthcheck = disableHealthcheck,
                forceRedownload = forceRedownload
            )
        )

        val exportService = ExportService(
            serverRepository = serverRepository,
            networkRepository = networkRepository,
            envVarsRepository = repos.envVarsRepository,
            modRepository = repos.modRepository,
            extraPortRepository = repos.extraPortRepository,
            proxyBackendRepository = repos.proxyBackendRepository,
            provisioning = provisioning,
            networkService = networkService
        )

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
        }

        fun createNode(): Uuid = transaction {
            Nodes.insert {
                it[Nodes.hostname] = "node-1"
                it[Nodes.displayName] = "node-1"
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

        context("exportServer") {

            lateinit var nodeId: Uuid
            lateinit var serverId: Uuid

            beforeTest {
                nodeId = createNode()
                val row = createServer(
                    name = "export-me",
                    displayName = "Export Me",
                    description = "A server to export",
                    nodeId = nodeId.toString(),
                    networkId = null,
                    serverType = "PAPER",
                    mcVersion = "1.21.4",
                    itzgImageTag = "latest",
                    memoryMb = 2048,
                    cpuLimitMillicores = 256,
                    containerListenPort = 25565,
                    containerProtocol = "UDP",
                    disableHealthcheck = true,
                    forceRedownload = true
                )
                serverId = row.id

                transaction {
                    ServerEnvVars.deleteWhere { ServerEnvVars.serverId eq serverId }
                    ServerEnvVars.insert {
                        it[ServerEnvVars.serverId] = serverId
                        it[ServerEnvVars.key] = "DIFFICULTY"
                        it[ServerEnvVars.value] = "hard"
                    }
                }

                transaction {
                    val s = Server.findById(serverId) ?: return@transaction
                    s.stopCommand = "save-all"
                    s.exposedExternally = true
                    s.customHostname = "my-host.example.com"
                    s.proxyMotd = null
                    s.expiresAt = null
                    s.backupSchedule = "0 3 * * *"
                    s.backupMaxCount = 14
                }
            }

            test("exports all core fields") {
                val export = exportService.exportServer(serverId)

                export.name shouldBe "export-me"
                export.displayName shouldBe "Export Me"
                export.description shouldBe "A server to export"
                export.serverType shouldBe "PAPER"
                export.mcVersion shouldBe "1.21.4"
                export.itzgImageTag shouldBe "latest"
                export.memoryMb shouldBe 2048
                export.cpuLimitMillicores shouldBe 256
                export.stopCommand shouldBe "save-all"
                export.exposedExternally shouldBe true
                export.customHostname shouldBe "my-host.example.com"
                export.configMode shouldBe "MANAGED"
                export.containerListenPort shouldBe 25565
                export.containerProtocol shouldBe "UDP"
                export.disableHealthcheck shouldBe true
                export.forceRedownload shouldBe true
                export.backupSchedule shouldBe "0 3 * * *"
                export.backupMaxCount shouldBe 14
                export.customServerJar.shouldBeNull()
            }

            test("exports env vars") {
                val export = exportService.exportServer(serverId)
                export.envVars.shouldNotBeNull()
                export.envVars!!.map { it.key } shouldContain "DIFFICULTY"
                export.envVars!!.first { it.key == "DIFFICULTY" }.value shouldBe "hard"
            }

            test("exports proxy fields as null for non-proxy server") {
                val export = exportService.exportServer(serverId)
                export.proxyMotd.shouldBeNull()
                export.proxyMaxPlayers.shouldBeNull()
                export.proxyForwardingMode.shouldBeNull()
                export.proxyBackends.shouldBeNull()
            }
        }

        context("importServer") {

            lateinit var nodeId: Uuid
            lateinit var exported: io.craftpanel.master.routes.dto.ServerExportData

            beforeTest {
                nodeId = createNode()
                val row = createServer(
                    name = "import-source",
                    displayName = "Import Source",
                    description = null,
                    nodeId = nodeId.toString(),
                    networkId = null,
                    serverType = "PAPER",
                    mcVersion = "1.21.4",
                    itzgImageTag = "latest",
                    memoryMb = 1024,
                    cpuLimitMillicores = 0
                )
                val sid = row.id

                transaction {
                    ServerEnvVars.deleteWhere { ServerEnvVars.serverId eq sid }
                    ServerEnvVars.insert {
                        it[ServerEnvVars.serverId] = sid
                        it[ServerEnvVars.key] = "MODE"
                        it[ServerEnvVars.value] = "creative"
                    }
                }

                exported = exportService.exportServer(sid)
            }

            test("creates server matching the export") {
                val imported = exportService.importServer(
                    exported.copy(name = "import-target"),
                    nodeId,
                    networkId = null
                )

                imported.name shouldBe "import-target"
                imported.displayName shouldBe "Import Source"
                imported.serverType shouldBe ServerType.PAPER
                imported.mcVersion shouldBe "1.21.4"
                imported.memoryMb shouldBe 1024
                imported.cpuLimitMillicores shouldBe 0

                val envVars = repos.envVarsRepository.getEnvVars(imported.id)
                envVars.map { it.key } shouldContain "MODE"
                envVars.first { it.key == "MODE" }.value shouldBe "creative"
            }

            test("throws on duplicate name") {
                val imported = exportService.importServer(exported.copy(name = "dup-name"), nodeId, null)
                imported.name shouldBe "dup-name"

                shouldThrow<ConflictException> {
                    exportService.importServer(exported.copy(name = "dup-name"), nodeId, null)
                }
            }

            test("throws on invalid node id") {
                val fakeNode = Uuid.random()
                shouldThrow<UnprocessableException> {
                    exportService.importServer(exported.copy(name = "bad-node"), fakeNode, null)
                }
            }
        }

        context("exportNetwork") {

            lateinit var nodeId: Uuid
            lateinit var netId: Uuid

            beforeTest {
                nodeId = createNode()
                val net = networkService.createNetwork(
                    CreateNetworkRequest(name = "test-net", description = "Test net", proxyPort = null)
                )
                netId = Uuid.parse(net.id)

                createServer(
                    name = "net-srv-1",
                    displayName = "Net Server 1",
                    description = null,
                    nodeId = nodeId.toString(),
                    networkId = net.id,
                    serverType = "PAPER",
                    mcVersion = "1.21.4",
                    itzgImageTag = "latest",
                    memoryMb = 512,
                    cpuLimitMillicores = 0
                )

                createServer(
                    name = "net-srv-2",
                    displayName = "Net Server 2",
                    description = null,
                    nodeId = nodeId.toString(),
                    networkId = net.id,
                    serverType = "VELOCITY",
                    mcVersion = "latest",
                    itzgImageTag = "latest",
                    memoryMb = 384,
                    cpuLimitMillicores = 0
                )
            }

            test("exports network with member servers") {
                val export = exportService.exportNetwork(netId)
                export.name shouldBe "test-net"
                export.description shouldBe "Test net"
                export.servers.shouldNotBeNull()
                export.servers!!.size shouldBe 2
                export.servers!!.map { it.name } shouldContain "net-srv-1"
                export.servers!!.map { it.name } shouldContain "net-srv-2"
            }

            test("importing network creates network and servers") {
                val export = exportService.exportNetwork(netId).copy(
                    name = "imported-net-test",
                    servers = exportService.exportNetwork(netId).servers?.map {
                        it.copy(name = "imported-${it.name}")
                    }
                )

                val imported = exportService.importNetwork(
                    export,
                    mapOf("imported-net-srv-1" to nodeId.toString(), "imported-net-srv-2" to nodeId.toString())
                )
                imported.name shouldBe "imported-net-test"
                imported.serverCount shouldBe 2

                val members = serverRepository.listByNetworkId(Uuid.parse(imported.id))
                members.size shouldBe 2
                members.map { it.displayName } shouldContain "Net Server 1"
                members.map { it.displayName } shouldContain "Net Server 2"
            }
        }
    })
