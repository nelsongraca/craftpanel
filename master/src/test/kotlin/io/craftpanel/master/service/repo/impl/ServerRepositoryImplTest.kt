package io.craftpanel.master.service.repo.impl

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.database.entity.Node
import io.craftpanel.master.database.entity.Server
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.ServerNetworks
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.service.repo.ServerRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class ServerRepositoryImplTest :
    FunSpec({
        lateinit var serverRepository: ServerRepository

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
            serverRepository = TestRepositories().serverRepository
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
                it[Nodes.totalCpuShares] = 1024
            }[Nodes.id].let { Uuid.parse(it.toString()) }
        }

        fun createNetwork(name: String = "net-1"): Uuid = transaction {
            ServerNetworks.insert {
                it[ServerNetworks.name] = name
            }[ServerNetworks.id].let { Uuid.parse(it.toString()) }
        }

        fun createServer(nodeId: Uuid, networkId: Uuid? = null, name: String = "server-1"): Uuid = transaction {
            val entity = Server.new {
                this.name = name
                this.displayName = name
                this.description = null
                this.nodeId = EntityID(nodeId, Nodes)
                this.networkId = networkId?.let { EntityID(it, ServerNetworks) }
                this.serverType = ServerType.VANILLA.toDb()
                this.mcVersion = "LATEST"
                this.itzgImageTag = "latest"
                this.hostPort = 25565
                this.memoryMb = 1024
                this.cpuShares = 1024
                this.configMode = "MANAGED"
                this.stopCommand = "stop"
            }
            entity.id.value
        }

        test("query methods work with entity-created data") {
            val nodeId = createNode()
            val id = createServer(nodeId)

            val found = serverRepository.findById(id)
            found shouldNotBe null
            found!!.name shouldBe "server-1"
            found.serverType shouldBe ServerType.VANILLA
            found.hostPort shouldBe 25565
            found.memoryMb shouldBe 1024
        }

        test("listAll returns all servers") {
            val nodeId = createNode()
            createServer(nodeId, name = "srv-a")
            createServer(nodeId, name = "srv-b")

            serverRepository.listAll().size shouldBe 2
        }

        test("findByName works") {
            val nodeId = createNode()
            createServer(nodeId, name = "unique-name")

            serverRepository.findByName("unique-name") shouldNotBe null
        }

        test("listByNodeId returns servers for a node") {
            val nodeId = createNode()
            createServer(nodeId, name = "srv-a")
            createServer(nodeId, name = "srv-b")

            serverRepository.listByNodeId(nodeId).size shouldBe 2
        }

        test("proxy settings round-trip through entity") {
            val nodeId = createNode()
            val id = createServer(nodeId, name = "proxy-1")

            transaction {
                val e = Server.findById(id) ?: return@transaction
                e.proxyMotd = "Welcome"
                e.proxyMaxPlayers = 50
                e.proxyForwardingMode = "legacy"
            }

            val row = serverRepository.findById(id)!!
            row.proxyMotd shouldBe "Welcome"
            row.proxyMaxPlayers shouldBe 50
            row.proxyForwardingMode shouldBe "legacy"
        }

        test("raw entity field mutation invalidates the cached row") {
            val nodeId = createNode()
            val netOld = createNetwork("net-old")
            val netNew = createNetwork("net-new")
            val id = createServer(nodeId, networkId = netOld, name = "srv-a")

            serverRepository.findById(id)!!.networkId shouldBe netOld

            transaction {
                Server.findById(id)?.let { it.networkId = EntityID(netNew, ServerNetworks) }
            }

            serverRepository.findById(id)!!.networkId shouldBe netNew
        }

        test("entity creation is readable with no cache pollution") {
            val nodeId = createNode()
            val id = createServer(nodeId, name = "created-server")

            val row = serverRepository.findById(id)
            row shouldNotBe null
            row!!.name shouldBe "created-server"
        }

        test("entity deletion evicts the cached row") {
            val nodeId = createNode()
            val id = createServer(nodeId, name = "doomed")

            serverRepository.findById(id) shouldNotBe null

            transaction {
                Server.findById(id)?.delete()
            }

            serverRepository.findById(id) shouldBe null
        }

        test("bulk networkId nullify (NetworkService.deleteNetwork pattern) evicts the cached row") {
            val nodeId = createNode()
            val net = createNetwork("net-bulk")
            val id = createServer(nodeId, networkId = net, name = "srv-bulk")

            serverRepository.findById(id)!!.networkId shouldBe net

            transaction {
                Servers.selectAll()
                    .where { Servers.networkId eq net }
                    .forEach { Server.findById(it[Servers.id])?.let { s -> s.networkId = null } }
            }

            serverRepository.findById(id)!!.networkId shouldBe null
        }

        test("write to a different server does not evict the cached row") {
            val nodeId = createNode()
            val idA = createServer(nodeId, name = "srv-a")
            val idB = createServer(nodeId, name = "srv-b")

            serverRepository.findById(idA)!!.name shouldBe "srv-a"

            transaction {
                Server.findById(idB)?.let { it.name = "srv-b-renamed" }
            }

            transaction {
                Servers.update({ Servers.id eq idA }) { it[Servers.name] = "srv-a-dsl" }
            }

            serverRepository.findById(idA)!!.name shouldBe "srv-a"
        }

        test("write to a different entity class does not evict the server cache") {
            val nodeId = createNode()
            val id = createServer(nodeId, name = "srv-a")

            serverRepository.findById(id)!!.name shouldBe "srv-a"

            transaction {
                Node.findById(nodeId)?.let { it.displayName = "renamed-node" }
            }

            transaction {
                Servers.update({ Servers.id eq id }) { it[Servers.name] = "srv-a-dsl" }
            }

            serverRepository.findById(id)!!.name shouldBe "srv-a"
        }

        test("updateNeedsRecreate and updateForwardingSecret invalidate via the hook") {
            val nodeId = createNode()
            val id = createServer(nodeId, name = "srv")

            serverRepository.findById(id)!!.needsRecreate shouldBe false

            serverRepository.updateNeedsRecreate(id, true)
            serverRepository.findById(id)!!.needsRecreate shouldBe true

            serverRepository.updateForwardingSecret(id, "secret-enc")
            serverRepository.findById(id)!!.forwardingSecretEnc shouldBe "secret-enc"
        }
    })
