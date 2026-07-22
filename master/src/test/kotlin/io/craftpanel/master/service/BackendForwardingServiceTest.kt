package io.craftpanel.master.service

import io.craftpanel.master.crypto.ForwardingSecretCipher
import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.service.repo.FakeRepositories
import io.craftpanel.master.service.repo.ProxyBackendInput
import io.craftpanel.master.service.repo.ServerRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlin.uuid.Uuid

class BackendForwardingServiceTest :
    FunSpec({

        val repos = FakeRepositories()
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

        fun createNode(hostname: String = "node-1"): Uuid {
            val id = Uuid.random()
            repos.serverRepository.create(
                name = "node-$id",
                displayName = hostname,
                description = null, nodeId = id, networkId = null,
                serverType = ServerType.VANILLA, mcVersion = "1.21.4",
                itzgImageTag = "latest", hostPort = 25565, memoryMb = 1024,
                cpuShares = 0, configMode = "MANAGED", stopCommand = "stop"
            )
            return id
        }

        fun createProxy(nodeId: Uuid, name: String = "proxy", forwardingMode: String = "MODERN"): Uuid {
            val id = serverRepository.create(
                name = name,
                displayName = name,
                description = null, nodeId = nodeId, networkId = null,
                serverType = ServerType.VELOCITY, mcVersion = "1.21.4",
                itzgImageTag = "latest", hostPort = 25577, memoryMb = 1024,
                cpuShares = 0, configMode = "MANAGED", stopCommand = "stop"
            ).id
            serverRepository.updateProxySettings(id, null, null, forwardingMode)
            return id
        }

        fun createBackend(nodeId: Uuid, name: String, type: ServerType, configMode: String = "MANAGED"): Uuid = serverRepository.create(
            name = name,
            displayName = name,
            description = null, nodeId = nodeId, networkId = null,
            serverType = type, mcVersion = "1.21.4",
            itzgImageTag = "latest", hostPort = 25565, memoryMb = 1024,
            cpuShares = 0, configMode = configMode, stopCommand = "stop"
        ).id

        beforeEach {
            writeCalls.clear()
            repos.envVars.clear()
            repos.proxyBackends.clear()
            repos.servers.clear()
        }

        test("writes patch + env + needs_recreate for each eligible Paper backend (modern)") {
            val nodeId = createNode()
            val proxyId = createProxy(nodeId)
            val paperId = createBackend(nodeId, "paper-1", ServerType.PAPER)
            val purpurId = createBackend(nodeId, "purpur-1", ServerType.PURPUR)

            repos.proxyBackendRepository.replaceProxyBackends(
                proxyId,
                listOf(ProxyBackendInput(paperId, "paper-1", 0), ProxyBackendInput(purpurId, "purpur-1", 1))
            )

            val warnings = service.applyToAllBackends(proxyId, "MODERN")

            warnings shouldBe emptyList()

            writeCalls.size shouldBe 2
            writeCalls[0].serverId shouldBe paperId
            writeCalls[0].path shouldBe "craftpanel-paper-global.yml"
            writeCalls[1].serverId shouldBe purpurId
            writeCalls[1].path shouldBe "craftpanel-paper-global.yml"

            val paperEnv = repos.envVars[paperId]!!.associate { it.key to it.value }
            paperEnv["ONLINE_MODE"] shouldBe "false"
            paperEnv["PATCH_DEFINITIONS"] shouldBe "/data/craftpanel-paper-global.yml"

            val purpurEnv = repos.envVars[purpurId]!!.associate { it.key to it.value }
            purpurEnv["ONLINE_MODE"] shouldBe "false"
            purpurEnv["PATCH_DEFINITIONS"] shouldBe "/data/craftpanel-paper-global.yml"

            serverRepository.findById(paperId)!!.needsRecreate shouldBe true
            serverRepository.findById(purpurId)!!.needsRecreate shouldBe true
        }

        test("warns for Vanilla backend (modern)") {
            val nodeId = createNode()
            val proxyId = createProxy(nodeId)
            val vanillaId = createBackend(nodeId, "vanilla-1", ServerType.VANILLA)
            val paperId = createBackend(nodeId, "paper-1", ServerType.PAPER)

            repos.proxyBackendRepository.replaceProxyBackends(
                proxyId,
                listOf(ProxyBackendInput(vanillaId, "vanilla-1", 0), ProxyBackendInput(paperId, "paper-1", 1))
            )

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

            repos.proxyBackendRepository.replaceProxyBackends(
                proxyId,
                listOf(ProxyBackendInput(manualId, "manual-1", 0), ProxyBackendInput(paperId, "paper-1", 1))
            )

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
            serverRepository.updateConfigMode(proxyId, "MANUAL")
            val paperId = createBackend(nodeId, "paper-1", ServerType.PAPER)
            repos.proxyBackendRepository.replaceProxyBackends(proxyId, listOf(ProxyBackendInput(paperId, "paper-1", 0)))

            val warnings = service.applyToAllBackends(proxyId, "MODERN")

            warnings shouldBe emptyList()
            writeCalls shouldBe emptyList()
        }

        test("mints secret once, reuses it") {
            val nodeId = createNode()
            val proxyId = createProxy(nodeId)
            val paperId = createBackend(nodeId, "paper-1", ServerType.PAPER)
            val purpurId = createBackend(nodeId, "purpur-1", ServerType.PURPUR)
            repos.proxyBackendRepository.replaceProxyBackends(
                proxyId,
                listOf(ProxyBackendInput(paperId, "paper-1", 0), ProxyBackendInput(purpurId, "purpur-1", 1))
            )

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

            repos.proxyBackendRepository.replaceProxyBackends(proxyId, listOf(ProxyBackendInput(spigotId, "spigot-1", 0)))

            val warnings = service.applyToAllBackends(proxyId, "LEGACY")
            warnings shouldBe emptyList()

            writeCalls.size shouldBe 1
            writeCalls[0].path shouldBe "craftpanel-spigot.yml"

            val env = repos.envVars[spigotId]!!.associate { it.key to it.value }
            env["PATCH_DEFINITIONS"] shouldBe "/data/craftpanel-spigot.yml"
        }

        test("legacy mode warns for Vanilla backend") {
            val nodeId = createNode()
            val proxyId = createProxy(nodeId, forwardingMode = "LEGACY")
            val vanillaId = createBackend(nodeId, "vanilla-1", ServerType.VANILLA)

            repos.proxyBackendRepository.replaceProxyBackends(proxyId, listOf(ProxyBackendInput(vanillaId, "vanilla-1", 0)))

            val warnings = service.applyToAllBackends(proxyId, "LEGACY")
            warnings.size shouldBe 1
            warnings[0].reason shouldContain "does not support forwarding"
        }

        test("warns for BUNGEEGUARD (out of scope)") {
            val nodeId = createNode()
            val proxyId = createProxy(nodeId, forwardingMode = "BUNGEEGUARD")
            val paperId = createBackend(nodeId, "paper-1", ServerType.PAPER)
            repos.proxyBackendRepository.replaceProxyBackends(proxyId, listOf(ProxyBackendInput(paperId, "paper-1", 0)))

            val warnings = service.applyToAllBackends(proxyId, "BUNGEEGUARD")
            warnings.size shouldBe 1
            warnings[0].reason shouldContain "Unsupported forwarding mode"
        }
    })
