package io.craftpanel.master.service.repo.impl
import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.database.entity.ServerEntity
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.ServerNetworks
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.ServerType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class ServerRepositoryImplTest :
    FunSpec({
        val repos = TestRepositories()
        val serverRepository = repos.serverRepository

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
                it[Nodes.totalCpuShares] = 1024
            }[Nodes.id].let { Uuid.parse(it.toString()) }
        }

        fun createNetwork(name: String = "net-1"): Uuid = transaction {
            ServerNetworks.insert {
                it[ServerNetworks.name] = name
            }[ServerNetworks.id].let { Uuid.parse(it.toString()) }
        }

        fun createServer(nodeId: Uuid, networkId: Uuid? = null, name: String = "server-1"): Uuid = transaction {
            val entity = ServerEntity.new {
                this.name = name
                this.displayName = name
                this.description = null
                this.nodeId = nodeId
                this.networkId = networkId
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
                val e = ServerEntity.findById(id) ?: return@transaction
                e.proxyMotd = "Welcome"
                e.proxyMaxPlayers = 50
                e.proxyForwardingMode = "legacy"
            }

            val row = serverRepository.findById(id)!!
            row.proxyMotd shouldBe "Welcome"
            row.proxyMaxPlayers shouldBe 50
            row.proxyForwardingMode shouldBe "legacy"
        }
    })
