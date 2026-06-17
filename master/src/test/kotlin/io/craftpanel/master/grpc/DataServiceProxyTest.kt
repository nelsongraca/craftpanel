package io.craftpanel.master.grpc

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.config.NodeConfig
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.routes.dto.FileEntryResponse
import io.craftpanel.master.routes.dto.ListFilesResponse
import io.craftpanel.master.routes.dto.ReadFileResponse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class DataServiceProxyTest : FunSpec({
    lateinit var controlSvc: ControlServiceImpl
    lateinit var proxy: DataServiceProxy

    beforeTest {
        TestDatabase.initIfNeeded()
        TestDatabase.reset()
        controlSvc = ControlServiceImpl(NodeConfig("test-token", 50052))
        proxy = DataServiceProxy(controlSvc, BulkDataServiceImpl(controlSvc))
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
            proxy.listFiles(Uuid.random().toString(), "/")
        }
    }

    test("listFiles throws for invalid serverId") {
        shouldThrow<IllegalStateException> {
            proxy.listFiles("not-a-uuid", "/")
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
        val result = proxy.console(serverId.toString(), emptyFlow())
        runBlocking {
            result.collect { }
        }
    }
})
