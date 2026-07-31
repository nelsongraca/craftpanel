package io.craftpanel.master.grpc

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.config.NodeConfig
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.domain.NodeStatus
import io.craftpanel.master.service.repo.FakeNodeRepository
import io.craftpanel.proto.IdentifyNodeResponse
import io.craftpanel.proto.identifyNodeRequest
import io.craftpanel.proto.nodeMetadata
import io.craftpanel.proto.registerNodeRequest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class NodeRegistrarTest :
    FunSpec({
        val nodeConfig = NodeConfig(bootstrapToken = "test-token", agentDataPort = 50052)

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
        }

        // -------------------------------------------------------------------------
        // registration / identify / verify — routed through NodeRepository seam,
        // no live DB required (proves the deepening: NodeRegistrar no longer
        // touches Exposed directly).
        // -------------------------------------------------------------------------

        test("registerNode creates a PENDING node via NodeRepository (no live DB)") {
            runBlocking {
                val fakeRepo = FakeNodeRepository()
                val registrar = NodeRegistrar(nodeConfig, fakeRepo)

                val response = registrar.registerNode(
                    registerNodeRequest {
                        bootstrapToken = "test-token"
                        metadata = nodeMetadata {
                            hostname = "fake-node"
                            publicIp = "1.2.3.4"
                            privateIp = "10.0.0.9"
                            totalRamMb = 2048
                            totalCpuShares = 1024
                            agentVersion = "1.0.0"
                        }
                    }
                )

                val stored = transaction {
                    Nodes.selectAll()
                        .where { Nodes.id eq Uuid.parse(response.nodeId) }
                        .firstOrNull()
                }
                stored.shouldNotBeNull()
                stored[Nodes.status] shouldBe "PENDING"
                stored[Nodes.hostname] shouldBe "fake-node"
                stored[Nodes.totalRamMb] shouldBe 2048
                stored[Nodes.totalCpuShares] shouldBe 1024
                stored[Nodes.agentVersion] shouldBe "1.0.0"
            }
        }

        test("identifyNode reports ACTIVE for a trusted node and updates lastSeen/privateIp via NodeRepository") {
            runBlocking {
                val fakeRepo = FakeNodeRepository()
                val registrar = NodeRegistrar(nodeConfig, fakeRepo)
                val rawKey = "raw-node-key"
                val keyHash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(rawKey.toByteArray())
                    .let {
                        java.util.HexFormat.of()
                            .formatHex(it)
                    }

                val createdNodeId = transaction {
                    Nodes.insert {
                        it[Nodes.displayName] = "n"
                        it[Nodes.hostname] = "n"
                        it[Nodes.publicIp] = "1.1.1.1"
                        it[Nodes.privateIp] = "10.0.0.1"
                        it[Nodes.tokenHash] = keyHash
                        it[Nodes.portRangeStart] = 25570
                        it[Nodes.portRangeEnd] = 26070
                        it[Nodes.status] = "ACTIVE"
                    }[Nodes.id].let { Uuid.parse(it.toString()) }
                }
                val created = fakeRepo.addNode(
                    id = createdNodeId,
                    displayName = "n",
                    hostname = "n",
                    publicIp = "1.1.1.1",
                    privateIp = "10.0.0.1",
                    tokenHash = keyHash,
                    portRangeStart = 25570,
                    portRangeEnd = 26070
                )
                fakeRepo.updateStatus(created.id, NodeStatus.ACTIVE)

                val response = registrar.identifyNode(
                    identifyNodeRequest {
                        nodeKey = rawKey
                        metadata = nodeMetadata {
                            publicIp = "9.9.9.9"
                            privateIp = "10.0.0.42"
                        }
                    }
                )

                response.status shouldBe IdentifyNodeResponse.IdentifyStatus.ACTIVE
                response.nodeId shouldBe created.id.toString()

                val updated = transaction {
                    Nodes.selectAll()
                        .where { Nodes.id eq created.id }
                        .firstOrNull()
                }
                updated.shouldNotBeNull()
                updated[Nodes.publicIp] shouldBe "9.9.9.9"
                updated[Nodes.privateIp] shouldBe "10.0.0.42"
                updated[Nodes.lastSeenAt].shouldNotBeNull()
            }
        }

        test("verifyNodeKey returns false when no node matches the token hash (no live DB)") {
            val fakeRepo = FakeNodeRepository()
            val registrar = NodeRegistrar(nodeConfig, fakeRepo)

            registrar.verifyNodeKey("some-random-key") shouldBe false
        }
    })
