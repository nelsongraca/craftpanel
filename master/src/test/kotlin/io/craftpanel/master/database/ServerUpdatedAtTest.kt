package io.craftpanel.master.database

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.database.entity.Server
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class ServerUpdatedAtTest :
    FunSpec({

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
        }

        fun createServer(): Uuid = transaction {
            val nodeId = Nodes.insert {
                it[Nodes.hostname] = "node-${Uuid.random()}"
                it[Nodes.displayName] = "Test"
                it[Nodes.publicIp] = "1.2.3.4"
                it[Nodes.privateIp] = "10.0.0.1"
                it[Nodes.tokenHash] = "a".repeat(64)
                it[Nodes.status] = "ACTIVE"
                it[Nodes.health] = "HEALTHY"
            }[Nodes.id].value

            Servers.insert {
                it[Servers.nodeId] = nodeId
                it[Servers.name] = "srv-${Uuid.random()}"
                it[Servers.hostPort] = 25565
                it[Servers.memoryMb] = 1024
            }[Servers.id].value
        }

        val epoch = LocalDateTime(2000, 1, 1, 0, 0)

        test("updatedAt is stamped when a server is mutated through the DAO") {
            val id = createServer()

            // Force a known-old timestamp via the table DSL (bypasses the entity hook).
            transaction {
                Servers.update({ Servers.id eq id }) { it[Servers.updatedAt] = epoch }
            }

            transaction {
                Server.findById(id)?.restartPending = true
            }

            transaction {
                (Server.findById(id)!!.updatedAt > epoch) shouldBe true
            }
        }

        test("updatedAt is not bumped by a read") {
            val id = createServer()

            transaction {
                Servers.update({ Servers.id eq id }) { it[Servers.updatedAt] = epoch }
            }

            transaction {
                Server.findById(id)!!.status shouldBe "STOPPED"
            }

            transaction {
                Server.findById(id)!!.updatedAt shouldBe epoch
            }
        }
    })
