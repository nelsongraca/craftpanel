package io.craftpanel.master.routes

import io.craftpanel.master.auth.ScopeType
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.domain.NodeHealth
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.service.repo.NodeRow
import io.craftpanel.master.service.repo.ServerRow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

private class FixedClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
}

private val FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z")

private fun serverRow(id: Uuid, networkId: Uuid? = null, nodeId: Uuid = Uuid.random()) = ServerRow(
    id = id, name = "test-server", displayName = "test-server",
    description = null, nodeId = nodeId, networkId = networkId,
    serverType = "VANILLA", mcVersion = "1.21.4", status = "HEALTHY",
    hostPort = 25565, memoryMb = 1024, cpuShares = 0,
    exposedExternally = false, publicSubdomain = null,
    dnsRecordId = null, dnsRecordName = null, customHostname = null,
    configMode = "MANAGED", stopCommand = "stop", itzgImageTag = "latest",
    needsRecreate = false, backupSchedule = null, backupMaxCount = 0,
    backupScheduleLastFired = null, lastPlayerCount = null,
    lastPlayerNames = null, lastPlayerUpdate = null, lastSeenAt = null,
    createdAt = "", updatedAt = ""
)

private fun nodeRow(id: Uuid) = NodeRow(
    id = id, displayName = "test-node", hostname = "test-node",
    publicIp = "5.6.7.8", privateIp = "10.0.0.2", tokenHash = "",
    status = "ACTIVE", health = "HEALTHY", totalRamMb = 16384, totalCpuShares = 0,
    systemRamUsedMb = null, portRangeStart = 25565, portRangeEnd = 25600,
    swarmActive = false, agentVersion = null, lastSeenAt = null,
    createdAt = "", updatedAt = ""
)

class DashboardEventFilterTest :
    FunSpec({

        fun filter(hasNodes: Boolean = true, canView: Boolean = true, networkId: Uuid? = null) = DashboardEventFilter(
            hasNodes = { hasNodes },
            canViewServer = { _, _ -> canView },
            serverNetworkId = { networkId },
            clock = FixedClock(FIXED_INSTANT)
        )

        test("NodeMetricsEvent passes gate and produces envelope") {
            val f = filter(hasNodes = true)
            val env = f.toEnvelope(
                AgentEvent.NodeMetricsEvent(
                    nodeId = "node-1", cpuPercent = 12.5, ramUsedMb = 100, ramTotalMb = 200,
                    netInBytes = 1, netOutBytes = 2, diskUsedBytes = 3, diskTotalBytes = 4,
                    recordedAt = FIXED_INSTANT
                )
            )
            env.shouldNotBeNull()
            env.type shouldBe "node.metrics"
            env.payload.jsonObject["node_id"]?.jsonPrimitive?.content shouldBe "node-1"
        }

        test("NodeMetricsEvent blocked when hasNodes=false") {
            val f = filter(hasNodes = false)
            f.toEnvelope(
                AgentEvent.NodeMetricsEvent(
                    nodeId = "node-1", cpuPercent = 1.0, ramUsedMb = 1, ramTotalMb = 1,
                    netInBytes = 1, netOutBytes = 1, diskUsedBytes = 1, diskTotalBytes = 1,
                    recordedAt = FIXED_INSTANT
                )
            ).shouldBeNull()
        }

        test("NodeStatusEvent uses injected clock and passes gate") {
            val f = filter(hasNodes = true)
            val env = f.toEnvelope(AgentEvent.NodeStatusEvent(nodeId = "node-1", health = NodeHealth.HEALTHY))
            env.shouldNotBeNull()
            env.type shouldBe "node.status"
            env.payload.jsonObject["recorded_at"]?.jsonPrimitive?.content shouldBe FIXED_INSTANT.toString()
        }

        test("NodeStatusEvent blocked when hasNodes=false") {
            val f = filter(hasNodes = false)
            f.toEnvelope(AgentEvent.NodeStatusEvent(nodeId = "node-1", health = NodeHealth.HEALTHY)).shouldBeNull()
        }

        test("ContainerMetricsEvent passes gate and produces envelope") {
            val sid = Uuid.random()
            val f = filter(canView = true)
            val env = f.toEnvelope(
                AgentEvent.ContainerMetricsEvent(
                    serverId = sid.toString(),
                    cpuPercent = 5.0,
                    ramUsedMb = 10,
                    netInBytes = 1,
                    netOutBytes = 2,
                    blockInBytes = 3,
                    blockOutBytes = 4,
                    recordedAt = FIXED_INSTANT
                )
            )
            env.shouldNotBeNull()
            env.type shouldBe "server.metrics"
        }

        test("ContainerMetricsEvent blocked when canViewServer=false") {
            val sid = Uuid.random()
            val f = filter(canView = false)
            f.toEnvelope(
                AgentEvent.ContainerMetricsEvent(
                    serverId = sid.toString(),
                    cpuPercent = 5.0,
                    ramUsedMb = 10,
                    netInBytes = 1,
                    netOutBytes = 2,
                    blockInBytes = 3,
                    blockOutBytes = 4,
                    recordedAt = FIXED_INSTANT
                )
            ).shouldBeNull()
        }

        test("ContainerMetricsEvent with unparseable serverId returns null") {
            val f = filter(canView = true)
            f.toEnvelope(
                AgentEvent.ContainerMetricsEvent(
                    serverId = "not-a-uuid",
                    cpuPercent = 5.0,
                    ramUsedMb = 10,
                    netInBytes = 1,
                    netOutBytes = 2,
                    blockInBytes = 3,
                    blockOutBytes = 4,
                    recordedAt = FIXED_INSTANT
                )
            ).shouldBeNull()
        }

        test("ServerStatusEvent passes gate and uses injected clock") {
            val sid = Uuid.random()
            val f = filter(canView = true)
            val env = f.toEnvelope(AgentEvent.ServerStatusEvent(serverId = sid.toString(), status = ServerStatus.HEALTHY))
            env.shouldNotBeNull()
            env.type shouldBe "server.status"
            env.payload.jsonObject["recorded_at"]?.jsonPrimitive?.content shouldBe FIXED_INSTANT.toString()
        }

        test("ServerStatusEvent blocked when canViewServer=false") {
            val sid = Uuid.random()
            val f = filter(canView = false)
            f.toEnvelope(AgentEvent.ServerStatusEvent(serverId = sid.toString(), status = ServerStatus.HEALTHY)).shouldBeNull()
        }

        test("ServerStatusEvent with unparseable serverId returns null") {
            val f = filter(canView = true)
            f.toEnvelope(AgentEvent.ServerStatusEvent(serverId = "nope", status = ServerStatus.HEALTHY)).shouldBeNull()
        }

        test("PlayerUpdateEvent passes gate and produces envelope") {
            val sid = Uuid.random()
            val f = filter(canView = true)
            val env = f.toEnvelope(
                AgentEvent.PlayerUpdateEvent(
                    serverId = sid.toString(),
                    playerCount = 2,
                    playerNames = listOf("a", "b"),
                    recordedAt = FIXED_INSTANT
                )
            )
            env.shouldNotBeNull()
            env.type shouldBe "server.players"
        }

        test("PlayerUpdateEvent blocked when canViewServer=false") {
            val sid = Uuid.random()
            val f = filter(canView = false)
            f.toEnvelope(
                AgentEvent.PlayerUpdateEvent(serverId = sid.toString(), playerCount = 0, playerNames = emptyList(), recordedAt = FIXED_INSTANT)
            ).shouldBeNull()
        }

        test("BackupProgressEvent passes gate and uses injected clock") {
            val sid = Uuid.random()
            val f = filter(canView = true)
            val env = f.toEnvelope(
                AgentEvent.BackupProgressEvent(serverId = sid.toString(), backupId = "b1", percentComplete = 42)
            )
            env.shouldNotBeNull()
            env.type shouldBe "server.backup.progress"
            env.payload.jsonObject["recorded_at"]?.jsonPrimitive?.content shouldBe FIXED_INSTANT.toString()
        }

        test("BackupProgressEvent blocked when canViewServer=false") {
            val sid = Uuid.random()
            val f = filter(canView = false)
            f.toEnvelope(AgentEvent.BackupProgressEvent(serverId = sid.toString(), backupId = "b1", percentComplete = 1)).shouldBeNull()
        }

        test("BackupCompleteEvent success produces COMPLETED status envelope") {
            val sid = Uuid.random()
            val f = filter(canView = true)
            val env = f.toEnvelope(
                AgentEvent.BackupCompleteEvent(
                    serverId = sid.toString(),
                    backupId = "b1",
                    success = true,
                    sizeBytes = 100,
                    errorMessage = "",
                    completedAt = FIXED_INSTANT
                )
            )
            env.shouldNotBeNull()
            env.type shouldBe "server.backup.complete"
            env.payload.jsonObject["status"]?.jsonPrimitive?.content shouldBe "COMPLETED"
            env.payload.jsonObject["error_message"].shouldBeNull()
        }

        test("BackupCompleteEvent failure carries errorMessage") {
            val sid = Uuid.random()
            val f = filter(canView = true)
            val env = f.toEnvelope(
                AgentEvent.BackupCompleteEvent(
                    serverId = sid.toString(),
                    backupId = "b1",
                    success = false,
                    sizeBytes = 0,
                    errorMessage = "boom",
                    completedAt = FIXED_INSTANT
                )
            )
            env.shouldNotBeNull()
            env.payload.jsonObject["status"]?.jsonPrimitive?.content shouldBe "FAILED"
            env.payload.jsonObject["error_message"]?.jsonPrimitive?.content shouldBe "boom"
        }

        test("BackupCompleteEvent blocked when canViewServer=false") {
            val sid = Uuid.random()
            val f = filter(canView = false)
            f.toEnvelope(
                AgentEvent.BackupCompleteEvent(serverId = sid.toString(), backupId = "b1", success = true, sizeBytes = 0, errorMessage = "", completedAt = FIXED_INSTANT)
            ).shouldBeNull()
        }

        test("AlertFiredEvent NODE scope gated by hasNodes") {
            val fBlocked = filter(hasNodes = false)
            fBlocked.toEnvelope(
                AgentEvent.AlertFiredEvent(
                    eventId = "e1",
                    thresholdId = "t1",
                    scopeType = ScopeType.NODE.name,
                    scopeId = "node-1",
                    metric = "cpu",
                    message = "high cpu",
                    firedAt = FIXED_INSTANT.toString(),
                    resolvedAt = null
                )
            ).shouldBeNull()

            val fAllowed = filter(hasNodes = true)
            fAllowed.toEnvelope(
                AgentEvent.AlertFiredEvent(
                    eventId = "e1",
                    thresholdId = "t1",
                    scopeType = ScopeType.NODE.name,
                    scopeId = "node-1",
                    metric = "cpu",
                    message = "high cpu",
                    firedAt = FIXED_INSTANT.toString(),
                    resolvedAt = null
                )
            ).shouldNotBeNull()
        }

        test("AlertFiredEvent SERVER scope gated by canViewServer") {
            val sid = Uuid.random()
            val fBlocked = filter(canView = false)
            fBlocked.toEnvelope(
                AgentEvent.AlertFiredEvent(
                    eventId = "e1",
                    thresholdId = "t1",
                    scopeType = ScopeType.SERVER.name,
                    scopeId = sid.toString(),
                    metric = "cpu",
                    message = "high cpu",
                    firedAt = FIXED_INSTANT.toString(),
                    resolvedAt = null
                )
            ).shouldBeNull()

            val fAllowed = filter(canView = true)
            fAllowed.toEnvelope(
                AgentEvent.AlertFiredEvent(
                    eventId = "e1",
                    thresholdId = "t1",
                    scopeType = ScopeType.SERVER.name,
                    scopeId = sid.toString(),
                    metric = "cpu",
                    message = "high cpu",
                    firedAt = FIXED_INSTANT.toString(),
                    resolvedAt = null
                )
            ).shouldNotBeNull()
        }

        test("AlertFiredEvent with unparseable SERVER scopeId returns null") {
            val f = filter(canView = true)
            f.toEnvelope(
                AgentEvent.AlertFiredEvent(
                    eventId = "e1",
                    thresholdId = "t1",
                    scopeType = ScopeType.SERVER.name,
                    scopeId = "not-a-uuid",
                    metric = "cpu",
                    message = "high cpu",
                    firedAt = FIXED_INSTANT.toString(),
                    resolvedAt = null
                )
            ).shouldBeNull()
        }

        test("AlertFiredEvent unresolved maps to alert.fired") {
            val f = filter(hasNodes = true, canView = true)
            val env = f.toEnvelope(
                AgentEvent.AlertFiredEvent(
                    eventId = "e1",
                    thresholdId = "t1",
                    scopeType = ScopeType.NODE.name,
                    scopeId = "node-1",
                    metric = "cpu",
                    message = "high cpu",
                    firedAt = FIXED_INSTANT.toString(),
                    resolvedAt = null
                )
            )
            env.shouldNotBeNull()
            env.type shouldBe "alert.fired"
        }

        test("AlertFiredEvent resolved maps to alert.resolved") {
            val f = filter(hasNodes = true, canView = true)
            val env = f.toEnvelope(
                AgentEvent.AlertFiredEvent(
                    eventId = "e1",
                    thresholdId = "t1",
                    scopeType = ScopeType.NODE.name,
                    scopeId = "node-1",
                    metric = "cpu",
                    message = "high cpu",
                    firedAt = FIXED_INSTANT.toString(),
                    resolvedAt = "2026-01-02T00:00:00Z"
                )
            )
            env.shouldNotBeNull()
            env.type shouldBe "alert.resolved"
        }

        test("snapshot filters servers by canViewServer") {
            val visible = Uuid.random()
            val hidden = Uuid.random()
            val f = DashboardEventFilter(
                hasNodes = { true },
                canViewServer = { id, _ -> id == visible },
                serverNetworkId = { null }
            )
            val env = f.snapshot(
                serverRows = listOf(serverRow(visible), serverRow(hidden)),
                latestMetrics = emptyMap(),
                nodeRows = listOf(nodeRow(Uuid.random()))
            )
            env.type shouldBe "snapshot"
            val serversArr = env.payload.jsonObject["servers"] as kotlinx.serialization.json.JsonArray
            serversArr.size shouldBe 1
            serversArr[0].jsonObject["id"]!!.jsonPrimitive.content shouldBe visible.toString()
        }

        test("snapshot: nodes empty when hasNodes=false") {
            val visible = Uuid.random()
            val f = DashboardEventFilter(
                hasNodes = { false },
                canViewServer = { _, _ -> true },
                serverNetworkId = { null }
            )
            val env = f.snapshot(
                serverRows = listOf(serverRow(visible)),
                latestMetrics = emptyMap(),
                nodeRows = listOf(nodeRow(Uuid.random()))
            )
            val payload = env.payload.jsonObject
            val nodesArr = payload["nodes"] as kotlinx.serialization.json.JsonArray
            nodesArr.size shouldBe 0
        }
    })
