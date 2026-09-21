package io.craftpanel.master.service

import io.craftpanel.master.service.repo.FakeNodeRepository
import io.craftpanel.master.service.repo.PortRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.uuid.Uuid

class PortAllocatorTest :
    FunSpec({

        // ── pickFreePort (pure) ────────────────────────────────────────────────

        test("returns first free port in range when none used") {
            val result = PortAllocator.pickFreePort(portRangeStart = 25570, portRangeEnd = 25580, usedPorts = emptySet())

            result shouldBe 25570
        }

        test("skips used ports and returns first free one") {
            val result = PortAllocator.pickFreePort(
                portRangeStart = 25570,
                portRangeEnd = 25580,
                usedPorts = setOf(25570, 25571, 25572),
            )

            result shouldBe 25573
        }

        test("returns null when entire range is used") {
            val result = PortAllocator.pickFreePort(
                portRangeStart = 25570,
                portRangeEnd = 25572,
                usedPorts = setOf(25570, 25571, 25572),
            )

            result.shouldBeNull()
        }

        // ── allocate (node-aware) ──────────────────────────────────────────────

        fun allocator(nodeId: Uuid, used: Set<Int>): PortAllocator {
            val nodes = FakeNodeRepository()
            nodes.addNode(id = nodeId, portRangeStart = 25570, portRangeEnd = 25572)
            val ports = object : PortRepository {
                override fun findUsedPortsOnNode(nodeId: Uuid): List<Int> = used.toList()
            }
            return PortAllocator(nodes, ports)
        }

        test("allocate returns the first free port on the node") {
            val nodeId = Uuid.random()

            allocator(nodeId, emptySet()).allocate(nodeId) shouldBe 25570
        }

        test("allocate honours a free preferred port") {
            val nodeId = Uuid.random()

            allocator(nodeId, emptySet()).allocate(nodeId, preferred = 25572) shouldBe 25572
        }

        test("allocate falls back to the next free port when the preferred one is used") {
            val nodeId = Uuid.random()

            allocator(nodeId, setOf(25570, 25572)).allocate(nodeId, preferred = 25570) shouldBe 25571
        }

        test("allocate throws PortExhaustedException when the range is full") {
            val nodeId = Uuid.random()

            shouldThrow<PortExhaustedException> {
                allocator(nodeId, setOf(25570, 25571, 25572)).allocate(nodeId)
            }
        }

        test("allocate throws NotFoundException for an unknown node") {
            val ports = object : PortRepository {
                override fun findUsedPortsOnNode(nodeId: Uuid): List<Int> = emptyList()
            }

            shouldThrow<NotFoundException> {
                PortAllocator(FakeNodeRepository(), ports).allocate(Uuid.random())
            }
        }
    })
