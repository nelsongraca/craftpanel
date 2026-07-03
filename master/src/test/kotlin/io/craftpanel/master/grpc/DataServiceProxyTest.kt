package io.craftpanel.master.grpc

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.config.NodeConfig
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.grpc.handlers.*
import io.craftpanel.master.service.BadGatewayException
import io.craftpanel.master.service.ConflictException
import io.craftpanel.master.service.ForbiddenException
import io.craftpanel.master.service.NodeStateReconciler
import io.craftpanel.master.service.NotFoundException
import io.craftpanel.master.service.UnprocessableException
import io.craftpanel.master.service.repo.NodeRepositoryImpl
import io.craftpanel.master.service.repo.ServerRepositoryImpl
import io.craftpanel.master.routes.dto.FileEntryResponse
import io.craftpanel.master.routes.dto.ListFilesResponse
import io.craftpanel.master.routes.dto.ReadFileResponse
import io.craftpanel.proto.ErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid
import java.util.concurrent.ConcurrentHashMap

class DataServiceProxyTest : FunSpec({
    lateinit var controlSvc: ControlServiceImpl
    lateinit var proxy: DataServiceProxy

    beforeTest {
        TestDatabase.initIfNeeded()
        TestDatabase.reset()
        val reconciler = NodeStateReconciler(ServerRepositoryImpl(), NodeRepositoryImpl())
        val agentEvents = MutableSharedFlow<io.craftpanel.master.domain.AgentEvent>(extraBufferCapacity = 1024)
        val dataOpContext = DataOpContext(ConcurrentHashMap(), ConcurrentHashMap())
        val nodeStateHandler = NodeStateHandler(agentEvents, reconciler)
        val nodeMetricsHandler = NodeMetricsHandler(agentEvents, reconciler)
        val containerMetricsHandler = ContainerMetricsHandler(agentEvents)
        val serverStatusHandler = ServerStatusHandler(agentEvents)
        val playerUpdateHandler = PlayerUpdateHandler(agentEvents)
        val backupHandler = BackupHandler(agentEvents)
        val migrationHandler = MigrationHandler(agentEvents)
        val dataOpResponseHandler = DataOpResponseHandler(dataOpContext)
        controlSvc = ControlServiceImpl(
            nodeConfig = NodeConfig("test-token", 50052),
            nodeStateReconciler = reconciler,
            agentEventsFlow = agentEvents,
            dataOpContext = dataOpContext,
            nodeStateHandler = nodeStateHandler,
            nodeMetricsHandler = nodeMetricsHandler,
            containerMetricsHandler = containerMetricsHandler,
            serverStatusHandler = serverStatusHandler,
            playerUpdateHandler = playerUpdateHandler,
            backupHandler = backupHandler,
            migrationHandler = migrationHandler,
            dataOpResponseHandler = dataOpResponseHandler,
        )
        proxy = DataServiceProxy(controlSvc, BulkDataServiceImpl(controlSvc), ServerRepositoryImpl())
    }

    fun createNode(): Uuid = transaction {
        Nodes.insert {
            it[Nodes.hostname] = "proxy-test-node"
            it[Nodes.displayName] = "Proxy Test Node"
            it[Nodes.publicIp] = "1.2.3.4"
            it[Nodes.privateIp] = "10.0.0.1"
            it[Nodes.tokenHash] = "a".repeat(64)
            it[Nodes.status] = "ACTIVE"
            it[Nodes.totalRamMb] = 8192
            it[Nodes.totalCpuShares] = 1024
        }[Nodes.id].let { Uuid.parse(it.toString()) }
    }

    fun createServer(nodeId: Uuid, status: String = "HEALTHY"): Uuid = transaction {
        Servers.insert {
            it[Servers.name] = "proxy-test-server"
            it[Servers.displayName] = "Proxy Test Server"
            it[Servers.nodeId] = nodeId
            it[Servers.serverType] = "VANILLA"
            it[Servers.mcVersion] = "LATEST"
            it[Servers.memoryMb] = 1024
            it[Servers.hostPort] = 25565
            it[Servers.status] = status
        }[Servers.id].let { Uuid.parse(it.toString()) }
    }

    // ── DB lookup errors ──────────────────────────────────────────────────────

    test("listFiles throws for unknown serverId") {
        shouldThrow<IllegalStateException> {
            proxy.listFiles(Uuid.random(), "/")
        }
    }

    // ── DTO shape ─────────────────────────────────────────────────────────────

    test("ListFilesResponse has correct structure") {
        val dto = ListFilesResponse(
            path = "/",
            entries = listOf(
                FileEntryResponse(
                    name = "server.properties",
                    isDirectory = false,
                    sizeBytes = 1024,
                    modifiedAt = null,
                    permissions = "rw-r--r--",
                )
            ),
        )
        dto.path shouldBe "/"
        dto.entries.size shouldBe 1
        dto.entries[0].name shouldBe "server.properties"
        dto.entries[0].sizeBytes shouldBe 1024L
    }

    test("ReadFileResponse has correct structure") {
        val dto = ReadFileResponse(
            path = "/server.properties",
            content = "level-name=world\n",
            encoding = "utf-8",
        )
        dto.path shouldBe "/server.properties"
        dto.content shouldBe "level-name=world\n"
        dto.encoding shouldBe "utf-8"
    }

    // ── console STOPPED guard ─────────────────────────────────────────────────

    test("console returns emptyFlow when server is STOPPED") {
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "STOPPED")
        val result = proxy.console(serverId, emptyFlow())
        runBlocking {
            result.collect { }
        }
    }

    // ── agentErrorToException ─────────────────────────────────────────────────

    test("agentErrorToException maps NOT_FOUND to NotFoundException") {
        proxy.agentErrorToException(ErrorCode.NOT_FOUND, "file not found")
            .shouldBeInstanceOf<NotFoundException>()
    }

    test("agentErrorToException maps ALREADY_EXISTS to ConflictException") {
        proxy.agentErrorToException(ErrorCode.ALREADY_EXISTS, "already exists")
            .shouldBeInstanceOf<ConflictException>()
    }

    test("agentErrorToException maps CONFLICT to ConflictException") {
        proxy.agentErrorToException(ErrorCode.CONFLICT, "not empty")
            .shouldBeInstanceOf<ConflictException>()
    }

    test("agentErrorToException maps PERMISSION_DENIED to ForbiddenException") {
        proxy.agentErrorToException(ErrorCode.PERMISSION_DENIED, "permission denied")
            .shouldBeInstanceOf<ForbiddenException>()
    }

    test("agentErrorToException maps UNPROCESSABLE to UnprocessableException") {
        proxy.agentErrorToException(ErrorCode.UNPROCESSABLE, "not enough space")
            .shouldBeInstanceOf<UnprocessableException>()
    }

    test("agentErrorToException maps UNAVAILABLE to BadGatewayException") {
        proxy.agentErrorToException(ErrorCode.UNAVAILABLE, "timed out")
            .shouldBeInstanceOf<BadGatewayException>()
    }

    test("agentErrorToException maps INTERNAL to BadGatewayException") {
        proxy.agentErrorToException(ErrorCode.INTERNAL, "internal error")
            .shouldBeInstanceOf<BadGatewayException>()
    }

    test("agentErrorToException maps ERROR_CODE_UNSPECIFIED to BadGatewayException") {
        proxy.agentErrorToException(ErrorCode.ERROR_CODE_UNSPECIFIED, "unknown error")
            .shouldBeInstanceOf<BadGatewayException>()
    }

    test("agentErrorToException preserves message") {
        val ex = proxy.agentErrorToException(ErrorCode.NOT_FOUND, "custom message")
        ex.message shouldBe "custom message"
    }
})
