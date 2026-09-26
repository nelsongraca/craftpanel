package io.craftpanel.master.scheduler

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.database.schema.ContainerMetrics
import io.craftpanel.master.database.schema.NodeMetrics
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.ServerStatusEvents
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.service.SettingsProvider
import io.craftpanel.master.service.repo.impl.NodeRepositoryImpl
import io.craftpanel.master.service.repo.impl.SettingsRepositoryImpl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

private class FixedClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
}

class RetentionJanitorTest :
    FunSpec({
        val repos = TestRepositories()
        val nodeRepository = NodeRepositoryImpl()
        val now = Instant.parse("2026-02-01T00:00:00Z")
        val old = Instant.parse("2025-12-01T00:00:00Z") // > 30 days before now
        val recent = Instant.parse("2026-01-15T00:00:00Z") // within 30 days

        lateinit var nodeId: Uuid
        lateinit var serverId: Uuid

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
            nodeId = transaction {
                Nodes.insert {
                    it[Nodes.hostname] = "janitor-node"
                    it[Nodes.displayName] = "janitor-node"
                    it[Nodes.publicIp] = "1.2.3.4"
                    it[Nodes.privateIp] = "10.0.0.1"
                    it[Nodes.tokenHash] = "b".repeat(64)
                    it[Nodes.status] = "ACTIVE"
                    it[Nodes.totalRamMb] = 8192
                    it[Nodes.totalCpuMillicores] = 0
                    it[Nodes.portRangeStart] = 25565
                    it[Nodes.portRangeEnd] = 25600
                }[Nodes.id].value
            }
            serverId = transaction {
                Servers.insert {
                    it[Servers.nodeId] = nodeId
                    it[Servers.name] = "janitor-server"
                    it[Servers.displayName] = "janitor-server"
                    it[Servers.serverType] = "VANILLA"
                    it[Servers.mcVersion] = "1.21.4"
                    it[Servers.itzgImageTag] = "latest"
                    it[Servers.hostPort] = 25565
                    it[Servers.memoryMb] = 1024
                    it[Servers.cpuLimitMillicores] = 0
                    it[Servers.status] = "HEALTHY"
                }[Servers.id].value
            }
        }

        fun seedStatusEvent(at: Instant, status: String) = transaction {
            ServerStatusEvents.insert {
                it[ServerStatusEvents.serverId] = EntityID(serverId, Servers)
                it[ServerStatusEvents.status] = status
                it[ServerStatusEvents.recordedAt] = at.toLocalDateTime(TimeZone.UTC)
            }
        }

        fun seedContainerMetrics(at: Instant) = transaction {
            ContainerMetrics.insert {
                it[ContainerMetrics.serverId] = EntityID(serverId, Servers)
                it[ContainerMetrics.recordedAt] = at.toLocalDateTime(TimeZone.UTC)
                it[ContainerMetrics.cpuPercent] = 1.0
                it[ContainerMetrics.ramUsedMb] = 128
                it[ContainerMetrics.netInBytes] = 1
                it[ContainerMetrics.netOutBytes] = 2
                it[ContainerMetrics.blockInBytes] = 3
                it[ContainerMetrics.blockOutBytes] = 4
            }
        }

        fun seedNodeMetrics(at: Instant) = transaction {
            NodeMetrics.insert {
                it[NodeMetrics.nodeId] = EntityID(nodeId, Nodes)
                it[NodeMetrics.recordedAt] = at.toLocalDateTime(TimeZone.UTC)
                it[NodeMetrics.cpuPercent] = 1.0
                it[NodeMetrics.ramUsedMb] = 128
                it[NodeMetrics.ramTotalMb] = 8192
                it[NodeMetrics.netInBytes] = 1
                it[NodeMetrics.netOutBytes] = 2
                it[NodeMetrics.diskUsedBytes] = 3
                it[NodeMetrics.diskTotalBytes] = 4
            }
        }

        fun count(table: Table): Long = transaction {
            table.selectAll().count()
        }

        fun janitor() = RetentionJanitor(
            settingsProvider = SettingsProvider(SettingsRepositoryImpl()),
            statusHistoryRepository = repos.statusHistoryRepository,
            containerMetricsRepository = repos.containerMetricsRepository,
            nodeRepository = nodeRepository,
            clock = FixedClock(now)
        )

        test("prunes rows older than the retention window and keeps recent ones") {
            seedStatusEvent(old, "STOPPED")
            seedStatusEvent(recent, "HEALTHY")
            seedContainerMetrics(old)
            seedContainerMetrics(recent)
            seedNodeMetrics(old)
            seedNodeMetrics(recent)

            janitor().prune()

            count(ServerStatusEvents) shouldBe 1
            count(ContainerMetrics) shouldBe 1
            count(NodeMetrics) shouldBe 1
        }

        test("is a no-op when nothing is stale") {
            seedStatusEvent(recent, "HEALTHY")
            seedContainerMetrics(recent)
            seedNodeMetrics(recent)

            janitor().prune()

            count(ServerStatusEvents) shouldBe 1
            count(ContainerMetrics) shouldBe 1
            count(NodeMetrics) shouldBe 1
        }
    })
