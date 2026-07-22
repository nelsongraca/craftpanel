package io.craftpanel.master.service

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.crypto.ForwardingSecretCipher
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.ServerNetworks
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.service.repo.ServerRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class ProxySettingsServiceTest :
    FunSpec({
        val repos = TestRepositories()
        val serverRepository: ServerRepository = repos.serverRepository
        val proxyConfigPatchService = ProxyConfigPatchService(repos.proxyBackendRepository, serverRepository)
        val backendForwardingService = BackendForwardingService(
            serverRepository = serverRepository,
            proxyBackendRepository = repos.proxyBackendRepository,
            envVarsRepository = repos.envVarsRepository,
            cipher = ForwardingSecretCipher(ByteArray(32) { 0x42 })
        ) { _, _, _ -> }
        val service = ProxySettingsService(serverRepository, proxyConfigPatchService, backendForwardingService) { _, _, _ -> }

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
                it[Nodes.totalCpuShares] = 0
                it[Nodes.portRangeStart] = 25565
                it[Nodes.portRangeEnd] = 25600
            }[Nodes.id].let { Uuid.parse(it.toString()) }
        }

        fun createServer(nodeId: Uuid, type: ServerType): Uuid = serverRepository.create(
            name = "srv-${Uuid.random()}",
            displayName = "srv",
            description = null,
            nodeId = nodeId,
            networkId = null,
            serverType = type,
            mcVersion = "1.21.4",
            itzgImageTag = "latest",
            hostPort = 25565,
            memoryMb = 1024,
            cpuShares = 0,
            configMode = "MANAGED",
            stopCommand = "stop"
        ).id

        test("rejects a non-proxy server with ConflictException") {
            val nodeId = createNode()
            val vanillaId = createServer(nodeId, ServerType.VANILLA)
            shouldThrow<ConflictException> {
                service.updateSettings(vanillaId, UpdateProxySettingsRequest(motd = "x", maxPlayers = 10, forwardingMode = "legacy"))
            }
        }

        test("persists settings and sets needs_recreate") {
            val nodeId = createNode()
            val proxyId = createServer(nodeId, ServerType.VELOCITY)

            service.updateSettings(proxyId, UpdateProxySettingsRequest(motd = "Welcome", maxPlayers = 40, forwardingMode = "legacy"))

            val row = serverRepository.findById(proxyId)!!
            row.proxyMotd shouldBe "Welcome"
            row.proxyMaxPlayers shouldBe 40
            row.proxyForwardingMode shouldBe "LEGACY"
            row.needsRecreate shouldBe true

            service.getSettings(proxyId) shouldBe ProxySettingsResponse("Welcome", 40, "LEGACY")
        }

        test("validates forwarding mode against proxy family") {
            val nodeId = createNode()
            val velocityId = createServer(nodeId, ServerType.VELOCITY)
            val bungeeId = createServer(nodeId, ServerType.BUNGEECORD)

            shouldThrow<UnprocessableException> {
                service.updateSettings(velocityId, UpdateProxySettingsRequest(null, null, "OFF"))
            }
            shouldThrow<UnprocessableException> {
                service.updateSettings(bungeeId, UpdateProxySettingsRequest(null, null, "MODERN"))
            }
            // bungee accepts LEGACY / OFF
            service.updateSettings(bungeeId, UpdateProxySettingsRequest(null, null, "off"))
            serverRepository.findById(bungeeId)!!.proxyForwardingMode shouldBe "OFF"
        }
    })
