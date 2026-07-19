package io.craftpanel.master.service.repo.impl

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.ServerNetworks
import io.craftpanel.master.database.schema.Servers
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
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

        fun createServer(nodeId: Uuid, networkId: Uuid? = null, name: String = "server-1"): Uuid = serverRepository.create(
            name = name,
            displayName = name,
            description = null,
            nodeId = nodeId,
            networkId = networkId,
            serverType = "VANILLA",
            mcVersion = "LATEST",
            itzgImageTag = "latest",
            hostPort = 25565,
            memoryMb = 1024,
            cpuShares = 1024,
            configMode = "MANAGED",
            stopCommand = "stop"
        ).id

        test("findById caches — a direct DB mutation bypassing the repo is not reflected until invalidated") {
            val nodeId = createNode()
            val id = createServer(nodeId)

            serverRepository.findById(id)!!.displayName shouldBe "server-1"

            // Mutate the row directly (bypassing the repo/cache) to prove findById
            // is actually serving from cache, not re-querying the DB every time.
            transaction {
                Servers.update({ Servers.id eq id }) {
                    it[Servers.displayName] = "mutated-directly"
                }
            }

            serverRepository.findById(id)!!.displayName shouldBe "server-1"
        }

        test("updateDetails invalidates the cache") {
            val nodeId = createNode()
            val id = createServer(nodeId)
            serverRepository.findById(id)

            serverRepository.updateDetails(id, displayName = "renamed", description = null, networkId = null, mcVersion = null, itzgImageTag = null)

            serverRepository.findById(id)!!.displayName shouldBe "renamed"
        }

        test("clearNetworkId invalidates the cache") {
            val nodeId = createNode()
            val networkId = createNetwork()
            val id = createServer(nodeId, networkId)
            serverRepository.findById(id)

            serverRepository.clearNetworkId(id)

            serverRepository.findById(id)!!.networkId shouldBe null
        }

        test("updateResources invalidates the cache") {
            val nodeId = createNode()
            val id = createServer(nodeId)
            serverRepository.findById(id)

            serverRepository.updateResources(id, memoryMb = 2048, cpuShares = 2048, itzgImageTag = null, needsRecreate = true)

            serverRepository.findById(id)!!.memoryMb shouldBe 2048
        }

        test("updateStatus invalidates the cache") {
            val nodeId = createNode()
            val id = createServer(nodeId)
            serverRepository.findById(id)

            serverRepository.updateStatus(id, status = "HEALTHY", lastSeenAt = null)

            serverRepository.findById(id)!!.status shouldBe "HEALTHY"
        }

        test("updateExposure invalidates the cache") {
            val nodeId = createNode()
            val id = createServer(nodeId)
            serverRepository.findById(id)

            serverRepository.updateExposure(id, exposedExternally = true, publicSubdomain = "sub", customHostname = null, dnsRecordId = null, dnsRecordName = null, needsRecreate = null)

            serverRepository.findById(id)!!.publicSubdomain shouldBe "sub"
        }

        test("updateNeedsRecreate invalidates the cache") {
            val nodeId = createNode()
            val id = createServer(nodeId)
            serverRepository.findById(id)

            serverRepository.updateNeedsRecreate(id, needsRecreate = true)

            serverRepository.findById(id)!!.needsRecreate shouldBe true
        }

        test("updatePlayerInfo invalidates the cache") {
            val nodeId = createNode()
            val id = createServer(nodeId)
            serverRepository.findById(id)

            serverRepository.updatePlayerInfo(id, playerCount = 5, playerNames = "a,b", lastUpdate = null)

            serverRepository.findById(id)!!.lastPlayerCount shouldBe 5
        }

        test("updateBackupSchedule invalidates the cache") {
            val nodeId = createNode()
            val id = createServer(nodeId)
            serverRepository.findById(id)

            serverRepository.updateBackupSchedule(id, schedule = "0 0 * * *", maxCount = 7)

            serverRepository.findById(id)!!.backupSchedule shouldBe "0 0 * * *"
        }

        test("updateBackupScheduleLastFired invalidates the cache") {
            val nodeId = createNode()
            val id = createServer(nodeId)
            serverRepository.findById(id)!!.backupScheduleLastFired shouldBe null

            serverRepository.updateBackupScheduleLastFired(id, lastFired = Clock.System.now())

            serverRepository.findById(id)!!.backupScheduleLastFired shouldNotBe null
        }

        test("updateConfigMode invalidates the cache") {
            val nodeId = createNode()
            val id = createServer(nodeId)
            serverRepository.findById(id)

            serverRepository.updateConfigMode(id, configMode = "MANUAL")

            serverRepository.findById(id)!!.configMode shouldBe "MANUAL"
        }

        test("updateStopCommand invalidates the cache") {
            val nodeId = createNode()
            val id = createServer(nodeId)
            serverRepository.findById(id)

            serverRepository.updateStopCommand(id, stopCommand = "end")

            serverRepository.findById(id)!!.stopCommand shouldBe "end"
        }

        test("delete invalidates the cache") {
            val nodeId = createNode()
            val id = createServer(nodeId)
            serverRepository.findById(id)

            serverRepository.delete(id)

            serverRepository.findById(id) shouldBe null
        }

        test("nullifyNetworkId invalidates only servers that were in that network") {
            val nodeId = createNode()
            val networkA = createNetwork("net-a")
            val networkB = createNetwork("net-b")
            val serverInA = createServer(nodeId, networkA, "server-a")
            val serverInB = createServer(nodeId, networkB, "server-b")

            // populate cache for both
            serverRepository.findById(serverInA)
            serverRepository.findById(serverInB)

            serverRepository.nullifyNetworkId(networkA)

            serverRepository.findById(serverInA)!!.networkId shouldBe null
            // server in network B must be unaffected — still cached with its original networkId
            serverRepository.findById(serverInB)!!.networkId shouldBe networkB
        }
    })
