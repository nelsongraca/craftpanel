package io.craftpanel.master.service

import io.craftpanel.master.auth.ScopeType
import io.craftpanel.master.service.repo.FakeAlertRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class AlertEvaluatorTest :
    FunSpec({

        val fixedInstant = Instant.parse("2025-06-01T12:00:00Z")
        val fixedClock = object : Clock {
            override fun now(): Instant = fixedInstant
        }

        fun evaluator(repo: FakeAlertRepository) = AlertEvaluator(repo, fixedClock)

        test("fires an alert when a metric crosses its threshold") {
            val repo = FakeAlertRepository()
            val nodeId = Uuid.random()
            val threshold = repo.createThreshold(ScopeType.NODE.name, nodeId, "cpu_percent", 80.0, null)

            val fired = evaluator(repo).evaluate(
                ScopeType.NODE,
                nodeId,
                "Node $nodeId",
                mapOf("cpu_percent" to 92.5)
            )

            fired.size shouldBe 1
            fired[0].thresholdId shouldBe threshold.id.toString()
            fired[0].scopeType shouldBe ScopeType.NODE.name
            fired[0].scopeId shouldBe nodeId.toString()
            fired[0].metric shouldBe "cpu_percent"
            fired[0].message shouldBe "Node $nodeId: cpu_percent at 92.5%"
            fired[0].resolvedAt shouldBe null
            repo.findOpenEvent(threshold.id) shouldNotBe null
        }

        test("does not fire again while an event is already open") {
            val repo = FakeAlertRepository()
            val nodeId = Uuid.random()
            repo.createThreshold(ScopeType.NODE.name, nodeId, "cpu_percent", 80.0, null)
            val ev = evaluator(repo)

            ev.evaluate(ScopeType.NODE, nodeId, "Node $nodeId", mapOf("cpu_percent" to 90.0)).size shouldBe 1
            ev.evaluate(ScopeType.NODE, nodeId, "Node $nodeId", mapOf("cpu_percent" to 95.0)).size shouldBe 0
        }

        test("resolves the open event when the metric normalises") {
            val repo = FakeAlertRepository()
            val serverId = Uuid.random()
            val threshold = repo.createThreshold(ScopeType.SERVER.name, serverId, "ram_percent", 75.0, null)
            val ev = evaluator(repo)

            ev.evaluate(ScopeType.SERVER, serverId, "Server $serverId", mapOf("ram_percent" to 90.0))
            val resolved = ev.evaluate(ScopeType.SERVER, serverId, "Server $serverId", mapOf("ram_percent" to 40.0))

            resolved.size shouldBe 1
            resolved[0].message shouldBe "Server $serverId: ram_percent normalised"
            resolved[0].resolvedAt shouldBe fixedInstant.toString()
            repo.findOpenEvent(threshold.id) shouldBe null
        }

        test("no notification when under threshold with nothing open") {
            val repo = FakeAlertRepository()
            val nodeId = Uuid.random()
            repo.createThreshold(ScopeType.NODE.name, nodeId, "cpu_percent", 80.0, null)

            evaluator(repo).evaluate(ScopeType.NODE, nodeId, "Node $nodeId", mapOf("cpu_percent" to 10.0)).size shouldBe 0
        }

        test("threshold whose metric is absent from the snapshot is skipped") {
            val repo = FakeAlertRepository()
            val nodeId = Uuid.random()
            repo.createThreshold(ScopeType.NODE.name, nodeId, "disk_percent", 80.0, null)

            evaluator(repo).evaluate(ScopeType.NODE, nodeId, "Node $nodeId", mapOf("cpu_percent" to 99.0)).size shouldBe 0
        }

        test("threshold with null value is skipped") {
            val repo = FakeAlertRepository()
            val nodeId = Uuid.random()
            repo.createThreshold(ScopeType.NODE.name, nodeId, "cpu_percent", null, "unhealthy")

            evaluator(repo).evaluate(ScopeType.NODE, nodeId, "Node $nodeId", mapOf("cpu_percent" to 99.0)).size shouldBe 0
        }

        test("only thresholds for the requested scope are evaluated") {
            val repo = FakeAlertRepository()
            val nodeId = Uuid.random()
            val otherNodeId = Uuid.random()
            repo.createThreshold(ScopeType.NODE.name, otherNodeId, "cpu_percent", 80.0, null)
            repo.createThreshold(ScopeType.SERVER.name, nodeId, "cpu_percent", 80.0, null)

            evaluator(repo).evaluate(ScopeType.NODE, nodeId, "Node $nodeId", mapOf("cpu_percent" to 99.0)).size shouldBe 0
        }

        test("value exactly at the threshold does not fire") {
            val repo = FakeAlertRepository()
            val nodeId = Uuid.random()
            repo.createThreshold(ScopeType.NODE.name, nodeId, "cpu_percent", 80.0, null)

            evaluator(repo).evaluate(ScopeType.NODE, nodeId, "Node $nodeId", mapOf("cpu_percent" to 80.0)).size shouldBe 0
        }
    })
