package io.craftpanel.master.service

import io.craftpanel.master.service.repo.FakeRepositories
import io.craftpanel.master.service.repo.FakeServerRepository.MutableContainerMetrics
import io.craftpanel.master.service.repo.fakeServerView
import io.craftpanel.master.service.repo.impl.GroupRepositoryImpl
import io.craftpanel.master.service.repo.impl.UserRepositoryImpl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ServerQueryServiceMetricsTest :
    FunSpec({
        val repos = FakeRepositories()

        fun service() = ServerQueryService(
            serverRepository = repos.serverRepository,
            userRepository = UserRepositoryImpl(),
            groupRepository = GroupRepositoryImpl(),
            containerMetricsRepository = repos.containerMetricsRepository,
            migrationRepository = repos.migrationRepository
        )

        lateinit var serverId: Uuid

        fun seedRow(heapUsedBytes: Long? = null, heapMaxBytes: Long? = null, nonHeapUsedBytes: Long? = null) = repos.containerMetrics.add(
            MutableContainerMetrics(
                serverId = serverId,
                recordedAt = "2025-01-01T00:00:00Z",
                cpuPercent = 10.0,
                ramUsedMb = 512,
                netInBytes = 1,
                netOutBytes = 2,
                blockInBytes = 3,
                blockOutBytes = 4,
                heapUsedBytes = heapUsedBytes,
                heapMaxBytes = heapMaxBytes,
                nonHeapUsedBytes = nonHeapUsedBytes
            )
        )

        beforeTest {
            repos.servers.clear()
            repos.containerMetrics.clear()
            serverId = Uuid.random()
            repos.servers[serverId] = fakeServerView(id = serverId, name = "srv")
        }

        test("includes heap and non-heap series from rows that carry a JVM sample") {
            seedRow(heapUsedBytes = 100, heapMaxBytes = 400, nonHeapUsedBytes = 25)

            val series = service().getMetrics(serverId, Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z")).series

            series.heapUsedBytes.map { it.v } shouldBe listOf(100L)
            series.heapMaxBytes.map { it.v } shouldBe listOf(400L)
            series.nonHeapUsedBytes.map { it.v } shouldBe listOf(25L)
        }

        test("omits null-JVM rows from the heap series without affecting other series") {
            seedRow()
            seedRow(heapUsedBytes = 100, heapMaxBytes = 400, nonHeapUsedBytes = 25)

            val series = service().getMetrics(serverId, Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z")).series

            // Both rows contributed to CPU, but only the JVM-bearing one to heap.
            series.cpuPercent.size shouldBe 2
            series.heapUsedBytes.map { it.v } shouldBe listOf(100L)
            series.nonHeapUsedBytes.map { it.v } shouldBe listOf(25L)
        }

        test("returns empty heap series when no row carries a JVM sample") {
            seedRow()

            val series = service().getMetrics(serverId, Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z")).series

            series.heapUsedBytes shouldBe emptyList()
            series.heapMaxBytes shouldBe emptyList()
            series.nonHeapUsedBytes shouldBe emptyList()
        }
    })
