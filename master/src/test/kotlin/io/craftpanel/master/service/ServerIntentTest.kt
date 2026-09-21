package io.craftpanel.master.service

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.database.entity.Server
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.domain.DesiredStatus
import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.service.repo.ServerRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class ServerIntentTest :
    FunSpec({
        val repos = TestRepositories()
        val serverRepository: ServerRepository = repos.serverRepository
        val intent = ServerIntent(serverRepository)

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

        fun createServer(nodeId: Uuid, desired: DesiredStatus? = null): Uuid = transaction {
            Server.new {
                this.name = "s-${Uuid.random()}"
                this.displayName = "s"
                this.nodeId = EntityID(nodeId, Nodes)
                this.serverType = ServerType.VANILLA.toDb()
                this.mcVersion = "1.21.4"
                this.itzgImageTag = "latest"
                this.hostPort = 25565
                this.memoryMb = 1024
                this.cpuLimitMillicores = 0
                this.status = "STOPPED"
                this.desiredStatus = desired?.toDb()
            }.id.value
        }

        fun desiredOf(serverId: Uuid): DesiredStatus? =
            DesiredStatus.fromDb(serverRepository.findById(serverId)!!.desiredStatus)

        test("record persists master's intent") {
            val serverId = createServer(createNode())
            intent.record(serverId, DesiredStatus.RUNNING)
            desiredOf(serverId) shouldBe DesiredStatus.RUNNING
        }

        test("withIntent keeps the intent when the action succeeds") {
            val serverId = createServer(createNode())
            runTest {
                intent.withIntent(serverId, DesiredStatus.RUNNING) { true }
            }
            desiredOf(serverId) shouldBe DesiredStatus.RUNNING
        }

        test("withIntent reverts to null when the action returns false") {
            val serverId = createServer(createNode(), desired = null)
            runTest {
                shouldThrow<BadGatewayException> {
                    intent.withIntent(serverId, DesiredStatus.RUNNING) { false }
                }
            }
            desiredOf(serverId) shouldBe null
        }

        test("withIntent reverts to the previous intent when the action returns false") {
            val serverId = createServer(createNode(), desired = DesiredStatus.STOPPED)
            runTest {
                shouldThrow<BadGatewayException> {
                    intent.withIntent(serverId, DesiredStatus.RUNNING) { false }
                }
            }
            desiredOf(serverId) shouldBe DesiredStatus.STOPPED
        }

        test("withIntent reverts and rethrows when the action throws") {
            val serverId = createServer(createNode(), desired = DesiredStatus.STOPPED)
            runTest {
                shouldThrow<IllegalStateException> {
                    intent.withIntent(serverId, DesiredStatus.RUNNING) { error("boom") }
                }
            }
            desiredOf(serverId) shouldBe DesiredStatus.STOPPED
        }
    })
