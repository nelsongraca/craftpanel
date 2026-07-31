package io.craftpanel.master.service

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.database.entity.EnvVar
import io.craftpanel.master.database.entity.ProxyBackend
import io.craftpanel.master.database.entity.Server
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.ProxyBackends
import io.craftpanel.master.database.schema.ServerEnvVars
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.crypto.ForwardingSecretCipher
import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.service.repo.ServerRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

class BackendForwardingServiceTest :
    FunSpec({

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
        }

        val repos = TestRepositories()
        val serverRepository: ServerRepository = repos.serverRepository
        val bytes = ByteArray(32) { 0x42 }
        val cipher = ForwardingSecretCipher(bytes)

        data class WriteCall(val serverId: Uuid, val path: String, val content: ByteArray)
        val writeCalls = mutableListOf<WriteCall>()

        val service = BackendForwardingService(
            serverRepository = serverRepository,
            proxyBackendRepository = repos.proxyBackendRepository,
            envVarsRepository = repos.envVarsRepository,
            cipher = cipher,
            writeFile = { id, path, content -> writeCalls.add(WriteCall(id, path, content)) }
        )

        fun createNode(hostname: String = "node-1"): Uuid = transaction {
            Nodes.insert {
                it[Nodes.hostname] = hostname
                it[Nodes.displayName] = hostname
                it[Nodes.publicIp] = "1.2.3.4"
                it[Nodes.privateIp] = "10.0.0.1"
                it[Nodes.tokenHash] = "a".repeat(64)
                it[Nodes.status] = "ACTIVE"
                it[Nodes.totalRamMb] = 8192
                it[Nodes.totalCpuShares] = 1024
            }[Nodes.id].let { Uuid.parse(it.toString()) }
        }

        fun createProxy(nodeId: Uuid, name: String = "proxy", forwardingMode: String = "MODERN"): Uuid = transaction {
            Servers.insert {
                it[Servers.name] = name
                it[Servers.displayName] = name
                it[Servers.nodeId] = nodeId
                it[Servers.serverType] = ServerType.VELOCITY.name
                it[Servers.mcVersion] = "1.21.4"
                it[Servers.hostPort] = 25577
                it[Servers.memoryMb] = 1024
                it[Servers.cpuShares] = 0
                it[Servers.configMode] = "MANAGED"
                it[Servers.stopCommand] = "stop"
                it[Servers.itzgImageTag] = "latest"
                it[Servers.proxyForwardingMode] = forwardingMode
            }[Servers.id].let { Uuid.parse(it.toString()) }
        }

        fun createBackend(nodeId: Uuid, name: String, type: ServerType, configMode: String = "MANAGED"): Uuid = transaction {
            Servers.insert {
                it[Servers.name] = name
                it[Servers.displayName] = name
                it[Servers.nodeId] = nodeId
                it[Servers.serverType] = type.name
                it[Servers.mcVersion] = "1.21.4"
                it[Servers.hostPort] = 25565
                it[Servers.memoryMb] = 1024
                it[Servers.cpuShares] = 0
                it[Servers.configMode] = configMode
                it[Servers.stopCommand] = "stop"
                it[Servers.itzgImageTag] = "latest"
            }[Servers.id].let { Uuid.parse(it.toString()) }
        }

        beforeEach {
            writeCalls.clear()
        }

        test("writes patch + env + needs_recreate for each eligible Paper backend (modern)") {
            val nodeId = createNode()
            val proxyId = createProxy(nodeId)
            val paperId = createBackend(nodeId, "paper-1", ServerType.PAPER)
            val purpurId = createBackend(nodeId, "purpur-1", ServerType.PURPUR)

            transaction {
                ProxyBackends.insert {
                    it[ProxyBackends.proxyServerId] = proxyId
                    it[ProxyBackends.backendServerId] = paperId
                    it[ProxyBackends.backendName] = "paper-1"
                    it[ProxyBackends.order] = 0
                }
                ProxyBackends.insert {
                    it[ProxyBackends.proxyServerId] = proxyId
                    it[ProxyBackends.backendServerId] = purpurId
                    it[ProxyBackends.backendName] = "purpur-1"
                    it[ProxyBackends.order] = 1
                }
            }

            val warnings = service.applyToAllBackends(proxyId, "MODERN")

            warnings shouldBe emptyList()

            writeCalls.size shouldBe 2
            writeCalls[0].serverId shouldBe paperId
            writeCalls[0].path shouldBe "craftpanel-paper-global.yml"
            writeCalls[1].serverId shouldBe purpurId
            writeCalls[1].path shouldBe "craftpanel-paper-global.yml"

            val paperEnv = transaction {
                EnvVar.find { (ServerEnvVars.serverId eq EntityID(paperId, Servers)) }
                    .associate { it.key to it.value }
            }
            paperEnv["ONLINE_MODE"] shouldBe "false"
            paperEnv["PATCH_DEFINITIONS"] shouldBe "/data/craftpanel-paper-global.yml"

            val purpurEnv = transaction {
                EnvVar.find { (ServerEnvVars.serverId eq EntityID(purpurId, Servers)) }
                    .associate { it.key to it.value }
            }
            purpurEnv["ONLINE_MODE"] shouldBe "false"
            purpurEnv["PATCH_DEFINITIONS"] shouldBe "/data/craftpanel-paper-global.yml"

            transaction { Server.findById(paperId)!!.needsRecreate shouldBe true }
            transaction { Server.findById(purpurId)!!.needsRecreate shouldBe true }
        }

        test("warns for Vanilla backend (modern)") {
            val nodeId = createNode()
            val proxyId = createProxy(nodeId)
            val vanillaId = createBackend(nodeId, "vanilla-1", ServerType.VANILLA)
            val paperId = createBackend(nodeId, "paper-1", ServerType.PAPER)

            transaction {
                ProxyBackends.insert {
                    it[ProxyBackends.proxyServerId] = proxyId
                    it[ProxyBackends.backendServerId] = vanillaId
                    it[ProxyBackends.backendName] = "vanilla-1"
                    it[ProxyBackends.order] = 0
                }
                ProxyBackends.insert {
                    it[ProxyBackends.proxyServerId] = proxyId
                    it[ProxyBackends.backendServerId] = paperId
                    it[ProxyBackends.backendName] = "paper-1"
                    it[ProxyBackends.order] = 1
                }
            }

            val warnings = service.applyToAllBackends(proxyId, "MODERN")

            warnings.size shouldBe 1
            warnings[0].backendId shouldBe vanillaId
            warnings[0].reason shouldContain "does not support forwarding"

            writeCalls.size shouldBe 1
            writeCalls[0].serverId shouldBe paperId
        }

        test("warns for MANUAL backend") {
            val nodeId = createNode()
            val proxyId = createProxy(nodeId)
            val manualId = createBackend(nodeId, "manual-1", ServerType.PAPER, configMode = "MANUAL")
            val paperId = createBackend(nodeId, "paper-1", ServerType.PAPER)

            transaction {
                ProxyBackends.insert {
                    it[ProxyBackends.proxyServerId] = proxyId
                    it[ProxyBackends.backendServerId] = manualId
                    it[ProxyBackends.backendName] = "manual-1"
                    it[ProxyBackends.order] = 0
                }
                ProxyBackends.insert {
                    it[ProxyBackends.proxyServerId] = proxyId
                    it[ProxyBackends.backendServerId] = paperId
                    it[ProxyBackends.backendName] = "paper-1"
                    it[ProxyBackends.order] = 1
                }
            }

            val warnings = service.applyToAllBackends(proxyId, "MODERN")

            warnings.size shouldBe 1
            warnings[0].backendId shouldBe manualId
            warnings[0].reason shouldContain "MANUAL"

            writeCalls.size shouldBe 1
            writeCalls[0].serverId shouldBe paperId
        }

        test("skips entire fan-out when proxy is MANUAL") {
            val nodeId = createNode()
            val proxyId = createProxy(nodeId)
            transaction { Servers.update({ Servers.id eq proxyId }) { it[Servers.configMode] = "MANUAL" } }
            val paperId = createBackend(nodeId, "paper-1", ServerType.PAPER)
            transaction {
                ProxyBackends.insert {
                    it[ProxyBackends.proxyServerId] = proxyId
                    it[ProxyBackends.backendServerId] = paperId
                    it[ProxyBackends.backendName] = "paper-1"
                    it[ProxyBackends.order] = 0
                }
            }

            val warnings = service.applyToAllBackends(proxyId, "MODERN")

            warnings shouldBe emptyList()
            writeCalls shouldBe emptyList()
        }

        test("mints secret once, reuses it") {
            val nodeId = createNode()
            val proxyId = createProxy(nodeId)
            val paperId = createBackend(nodeId, "paper-1", ServerType.PAPER)
            val purpurId = createBackend(nodeId, "purpur-1", ServerType.PURPUR)
            transaction {
                ProxyBackends.insert {
                    it[ProxyBackends.proxyServerId] = proxyId
                    it[ProxyBackends.backendServerId] = paperId
                    it[ProxyBackends.backendName] = "paper-1"
                    it[ProxyBackends.order] = 0
                }
                ProxyBackends.insert {
                    it[ProxyBackends.proxyServerId] = proxyId
                    it[ProxyBackends.backendServerId] = purpurId
                    it[ProxyBackends.backendName] = "purpur-1"
                    it[ProxyBackends.order] = 1
                }
            }

            service.applyToAllBackends(proxyId, "MODERN")

            val enc = serverRepository.findById(proxyId)!!.forwardingSecretEnc
            enc shouldNotBe null

            val decrypted = cipher.decrypt(enc!!)
            decrypted.length shouldBe 32

            writeCalls.size shouldBe 2
            val contentA = writeCalls[0].content.decodeToString()
            val contentB = writeCalls[1].content.decodeToString()
            contentA shouldBe contentB
        }

        test("legacy mode writes spigot.yml patch") {
            val nodeId = createNode()
            val proxyId = createProxy(nodeId, forwardingMode = "LEGACY")
            val spigotId = createBackend(nodeId, "spigot-1", ServerType.SPIGOT)

            transaction {
                ProxyBackends.insert {
                    it[ProxyBackends.proxyServerId] = proxyId
                    it[ProxyBackends.backendServerId] = spigotId
                    it[ProxyBackends.backendName] = "spigot-1"
                    it[ProxyBackends.order] = 0
                }
            }

            val warnings = service.applyToAllBackends(proxyId, "LEGACY")
            warnings shouldBe emptyList()

            writeCalls.size shouldBe 1
            writeCalls[0].path shouldBe "craftpanel-spigot.yml"

            val env = transaction {
                EnvVar.find { (ServerEnvVars.serverId eq EntityID(spigotId, Servers)) }
                    .associate { it.key to it.value }
            }
            env["PATCH_DEFINITIONS"] shouldBe "/data/craftpanel-spigot.yml"
        }

        test("legacy mode warns for Vanilla backend") {
            val nodeId = createNode()
            val proxyId = createProxy(nodeId, forwardingMode = "LEGACY")
            val vanillaId = createBackend(nodeId, "vanilla-1", ServerType.VANILLA)

            transaction {
                ProxyBackends.insert {
                    it[ProxyBackends.proxyServerId] = proxyId
                    it[ProxyBackends.backendServerId] = vanillaId
                    it[ProxyBackends.backendName] = "vanilla-1"
                    it[ProxyBackends.order] = 0
                }
            }

            val warnings = service.applyToAllBackends(proxyId, "LEGACY")
            warnings.size shouldBe 1
            warnings[0].reason shouldContain "does not support forwarding"
        }

        test("warns for BUNGEEGUARD (out of scope)") {
            val nodeId = createNode()
            val proxyId = createProxy(nodeId, forwardingMode = "BUNGEEGUARD")
            val paperId = createBackend(nodeId, "paper-1", ServerType.PAPER)
            transaction {
                ProxyBackends.insert {
                    it[ProxyBackends.proxyServerId] = proxyId
                    it[ProxyBackends.backendServerId] = paperId
                    it[ProxyBackends.backendName] = "paper-1"
                    it[ProxyBackends.order] = 0
                }
            }

            val warnings = service.applyToAllBackends(proxyId, "BUNGEEGUARD")
            warnings.size shouldBe 1
            warnings[0].reason shouldContain "Unsupported forwarding mode"
        }
    })
