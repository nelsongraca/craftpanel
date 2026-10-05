package io.craftpanel.master.service

import io.craftpanel.master.TestAgentGateway
import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.database.entity.EnvVar
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.testServerSpecSync
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

class ServerSpecSyncTest :
    FunSpec({

        val repos = TestRepositories()
        lateinit var serverId: Uuid

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
            val nodeId = transaction {
                Nodes.insert {
                    it[Nodes.hostname] = "n-${Uuid.random()}"
                    it[Nodes.displayName] = "n"
                    it[Nodes.publicIp] = "1.2.3.4"
                    it[Nodes.privateIp] = "10.0.0.1"
                    it[Nodes.tokenHash] = "a".repeat(64)
                    it[Nodes.status] = "ACTIVE"
                    it[Nodes.totalRamMb] = 8192
                    it[Nodes.totalCpuMillicores] = 0
                    it[Nodes.portRangeStart] = 25565
                    it[Nodes.portRangeEnd] = 25600
                }[Nodes.id]
            }
            serverId = transaction {
                Servers.insert {
                    it[Servers.nodeId] = nodeId
                    it[Servers.name] = "s-${Uuid.random()}"
                    it[Servers.hostPort] = 25565
                    it[Servers.memoryMb] = 1024
                    it[Servers.status] = "HEALTHY"
                    it[Servers.desiredStatus] = "RUNNING"
                }[Servers.id].value
            }
        }

        fun addEnvVar(key: String, value: String) = transaction {
            EnvVar.new {
                this.serverId = EntityID(serverId, Servers)
                this.key = key
                this.value = value
            }
        }

        fun pending(): Boolean = transaction {
            Servers.selectAll().where { Servers.id eq serverId }.first()[Servers.restartPending]
        }

        fun delivered(): Boolean = transaction {
            Servers.selectAll().where { Servers.id eq serverId }.first()[Servers.specDelivered]
        }

        // Reproduction of the reported bug: adding an env var must push the refreshed spec to the
        // agent, otherwise a later (in-server) restart recreates from the stale spec.
        test("a spec change on a RUNNING server flags pending and pushes the refreshed spec") {
            val gw = TestAgentGateway()
            val sync = testServerSpecSync(repos, gw)

            sync.reconcile(serverId) { addEnvVar("MARKER", "changed") }

            gw.sent.size shouldBe 1
            gw.sent.single().second.serverDesiredState.forceRestart shouldBe false
            gw.sent.single().second.serverDesiredState.spec.envVarsMap["MARKER"] shouldBe "changed"
            pending() shouldBe true
            delivered() shouldBe true
        }

        test("a mutation that does not change the spec flags nothing and pushes nothing") {
            val gw = TestAgentGateway()
            val sync = testServerSpecSync(repos, gw)

            sync.reconcile(serverId) { /* no change */ }

            gw.sent shouldBe emptyList()
            pending() shouldBe false
        }

        test("a spec change while stopped flags pending without pushing (the next start carries it)") {
            transaction {
                Servers.update({ Servers.id eq serverId }) {
                    it[Servers.desiredStatus] = "STOPPED"
                    it[Servers.status] = "STOPPED"
                }
            }
            val gw = TestAgentGateway()
            val sync = testServerSpecSync(repos, gw)

            sync.reconcile(serverId) { addEnvVar("MARKER", "changed") }

            gw.sent shouldBe emptyList()
            pending() shouldBe true
            delivered() shouldBe true
        }

        test("a failed push records spec_delivered=false so the marker is not cleared early") {
            val gw = TestAgentGateway(sendResult = false)
            val sync = testServerSpecSync(repos, gw)

            sync.reconcile(serverId) { addEnvVar("MARKER", "changed") }

            pending() shouldBe true
            delivered() shouldBe false
        }

        test("restartRequired only flags pending and leaves delivery untouched") {
            val gw = TestAgentGateway()
            val sync = testServerSpecSync(repos, gw)
            sync.markDelivered(serverId, false)

            sync.restartRequired(serverId)

            pending() shouldBe true
            delivered() shouldBe false
            gw.sent shouldBe emptyList()
        }
    })
