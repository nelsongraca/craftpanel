package io.craftpanel.master.service

import io.craftpanel.master.service.repo.FakeNodeRepository
import io.craftpanel.master.service.repo.FakeServerRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.uuid.Uuid

class ResourceCapacityCheckerTest : FunSpec({

    test("fits within capacity returns Ok") {
        val nodes = FakeNodeRepository()
        val servers = FakeServerRepository()
        val checker = ResourceCapacityChecker(servers)

        val node = nodes.create("node-1", "host", "1.2.3.4", "10.0.0.1", "hash", 25570, 26070)
        nodes.setCapacity(node.id, totalRamMb = 4096, totalCpuShares = 2048)
        val freshNode = nodes.findById(node.id)!!

        val result = checker.check(freshNode, excludeServerId = null, memoryMb = 1024, cpuShares = 512)

        result shouldBe CapacityResult.Ok
    }

    test("exceeding RAM capacity returns InsufficientRam") {
        val nodes = FakeNodeRepository()
        val servers = FakeServerRepository()
        val checker = ResourceCapacityChecker(servers)

        val node = nodes.create("node-1", "host", "1.2.3.4", "10.0.0.1", "hash", 25570, 26070)
        nodes.setCapacity(node.id, totalRamMb = 1024, totalCpuShares = 2048)
        val freshNode = nodes.findById(node.id)!!

        val result = checker.check(freshNode, excludeServerId = null, memoryMb = 2048, cpuShares = 512)

        result shouldBe CapacityResult.InsufficientRam
    }

    test("exceeding CPU capacity returns InsufficientCpu") {
        val nodes = FakeNodeRepository()
        val servers = FakeServerRepository()
        val checker = ResourceCapacityChecker(servers)

        val node = nodes.create("node-1", "host", "1.2.3.4", "10.0.0.1", "hash", 25570, 26070)
        nodes.setCapacity(node.id, totalRamMb = 8192, totalCpuShares = 1024)
        val freshNode = nodes.findById(node.id)!!

        val result = checker.check(freshNode, excludeServerId = null, memoryMb = 1024, cpuShares = 2048)

        result shouldBe CapacityResult.InsufficientCpu
    }

    test("totalCpuShares of 0 means unlimited CPU, never blocks") {
        val nodes = FakeNodeRepository()
        val servers = FakeServerRepository()
        val checker = ResourceCapacityChecker(servers)

        val node = nodes.create("node-1", "host", "1.2.3.4", "10.0.0.1", "hash", 25570, 26070)
        nodes.setCapacity(node.id, totalRamMb = 8192, totalCpuShares = 0)
        val freshNode = nodes.findById(node.id)!!

        val result = checker.check(freshNode, excludeServerId = null, memoryMb = 1024, cpuShares = 999999)

        result shouldBe CapacityResult.Ok
    }

    test("existing servers on node count toward used RAM") {
        val nodes = FakeNodeRepository()
        val servers = FakeServerRepository()
        val checker = ResourceCapacityChecker(servers)

        val node = nodes.create("node-1", "host", "1.2.3.4", "10.0.0.1", "hash", 25570, 26070)
        nodes.setCapacity(node.id, totalRamMb = 2048, totalCpuShares = 2048)
        val freshNode = nodes.findById(node.id)!!
        servers.create(
            name = "existing", displayName = "existing", description = null,
            nodeId = node.id, networkId = null, serverType = "VANILLA",
            mcVersion = "1.21.4", itzgImageTag = "latest", hostPort = 25565,
            memoryMb = 1500, cpuShares = 0, configMode = "MANAGED", stopCommand = "stop",
        )

        val result = checker.check(freshNode, excludeServerId = null, memoryMb = 1024, cpuShares = 0)

        result shouldBe CapacityResult.InsufficientRam
    }

    test("excludeServerId excludes that server's own usage from the check") {
        val nodes = FakeNodeRepository()
        val servers = FakeServerRepository()
        val checker = ResourceCapacityChecker(servers)

        val node = nodes.create("node-1", "host", "1.2.3.4", "10.0.0.1", "hash", 25570, 26070)
        nodes.setCapacity(node.id, totalRamMb = 2048, totalCpuShares = 2048)
        val freshNode = nodes.findById(node.id)!!
        val existing = servers.create(
            name = "existing", displayName = "existing", description = null,
            nodeId = node.id, networkId = null, serverType = "VANILLA",
            mcVersion = "1.21.4", itzgImageTag = "latest", hostPort = 25565,
            memoryMb = 1500, cpuShares = 0, configMode = "MANAGED", stopCommand = "stop",
        )

        val result = checker.check(freshNode, excludeServerId = existing.id, memoryMb = 2048, cpuShares = 0)

        result shouldBe CapacityResult.Ok
    }

    test("node systemRamUsedMb is factored in via maxOf against server-tracked usage") {
        val nodes = FakeNodeRepository()
        val servers = FakeServerRepository()
        val checker = ResourceCapacityChecker(servers)

        val node = nodes.create("node-1", "host", "1.2.3.4", "10.0.0.1", "hash", 25570, 26070)
        nodes.setCapacity(node.id, totalRamMb = 2048, totalCpuShares = 2048, systemRamUsedMb = 1800)
        val freshNode = nodes.findById(node.id)!!

        val result = checker.check(freshNode, excludeServerId = null, memoryMb = 500, cpuShares = 0)

        result shouldBe CapacityResult.InsufficientRam
    }
})
