package io.craftpanel.master.service

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.config.NodeConfig
import io.craftpanel.master.database.entity.Node
import io.craftpanel.master.domain.NodeStatus
import io.craftpanel.master.service.repo.impl.NodeRepositoryImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class NodeRegistrationServiceTest :
    FunSpec({
        val service = NodeRegistrationService(NodeConfig("test-token", 50052), NodeRepositoryImpl())

        fun metadata(hostname: String = "node-1") = NodeMetadata(
            hostname = hostname,
            publicIp = "1.2.3.4",
            privateIp = "10.0.0.1",
            totalRamMb = 8192,
            reservedRamMb = 1024,
            totalCpuMillicores = 4000,
            reservedCpuMillicores = 1000,
            agentVersion = "1.0.0"
        )

        fun setStatus(id: Uuid, status: NodeStatus) {
            transaction { Node.findById(id)?.status = status.toDb() }
        }

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
        }

        test("register persists a PENDING node with the minted key hash and returns the raw key") {
            val registered = service.register("test-token", metadata())

            val stored = transaction { Node.findById(registered.nodeId) }
            stored.shouldNotBeNull()
            stored.status shouldBe "PENDING"
            stored.hostname shouldBe "node-1"
            stored.publicIp shouldBe "1.2.3.4"
            stored.totalRamMb shouldBe 8192
            stored.reservedRamMb shouldBe 1024
            stored.agentVersion shouldBe "1.0.0"
            stored.tokenHash shouldBe service.hashKey(registered.rawKey)
            stored.tokenHash shouldNotBe registered.rawKey
        }

        test("register rejects a wrong bootstrap token") {
            shouldThrow<IllegalArgumentException> {
                service.register("wrong-token", metadata())
            }
        }

        test("identify refreshes identity and lastSeenAt, and reports the node status") {
            val registered = service.register("test-token", metadata())
            setStatus(registered.nodeId, NodeStatus.ACTIVE)

            val identified = service.identify(
                registered.rawKey,
                metadata(hostname = "renamed").copy(publicIp = "9.9.9.9", totalRamMb = 4096)
            )

            identified.status shouldBe NodeStatus.ACTIVE
            identified.nodeId shouldBe registered.nodeId
            val stored = transaction { Node.findById(registered.nodeId) }!!
            stored.publicIp shouldBe "9.9.9.9"
            stored.hostname shouldBe "renamed"
            stored.totalRamMb shouldBe 4096
            stored.lastSeenAt shouldNotBe null
        }

        test("identify reports REJECTED with no id for an unknown key") {
            val identified = service.identify("unknown-key", metadata())

            identified.status shouldBe NodeStatus.REJECTED
            identified.nodeId shouldBe null
        }

        test("requireActive throws for a PENDING node and passes for an ACTIVE node") {
            val registered = service.register("test-token", metadata())

            val failure = shouldThrow<NodeNotActiveException> { service.requireActive(registered.nodeId) }
            failure.message shouldContain "pending admin approval"

            setStatus(registered.nodeId, NodeStatus.ACTIVE)
            service.requireActive(registered.nodeId)
        }

        test("isActive is true only for an ACTIVE node key") {
            val registered = service.register("test-token", metadata())

            service.isActive(registered.rawKey) shouldBe false
            service.isActive("unknown-key") shouldBe false

            setStatus(registered.nodeId, NodeStatus.ACTIVE)
            service.isActive(registered.rawKey) shouldBe true
        }

        test("mintKey is random and hashKey is deterministic") {
            val a = service.mintKey()
            val b = service.mintKey()
            a shouldNotBe b
            service.hashKey(a) shouldBe service.hashKey(a)
        }
    })
