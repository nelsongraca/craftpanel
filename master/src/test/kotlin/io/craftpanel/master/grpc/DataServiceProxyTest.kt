package io.craftpanel.master.grpc

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.config.NodeConfig
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.routes.dto.FileEntryResponse
import io.craftpanel.master.routes.dto.ListFilesResponse
import io.craftpanel.master.routes.dto.ReadFileResponse
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class DataServiceProxyTest {

    private lateinit var controlSvc: ControlServiceImpl
    private lateinit var proxy: DataServiceProxy

    @BeforeTest
    fun setup() {
        TestDatabase.initIfNeeded()
        TestDatabase.reset()
        controlSvc = ControlServiceImpl(NodeConfig("test-token", 50052))
        proxy = DataServiceProxy(controlSvc, BulkDataServiceImpl(controlSvc))
    }

    private fun createNode(): Uuid = transaction {
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

    private fun createServer(nodeId: Uuid, status: String = "HEALTHY"): Uuid = transaction {
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

    @Test
    fun `listFiles throws for unknown serverId`() = runBlocking {
        assertFailsWith<IllegalStateException> {
            proxy.listFiles(
                Uuid.random()
                    .toString(), "/"
            )
        }
    }

    @Test
    fun `listFiles throws for invalid serverId`() = runBlocking {
        assertFailsWith<IllegalStateException> {
            proxy.listFiles("not-a-uuid", "/")
        }
    }

    // ── DTO shape ─────────────────────────────────────────────────────────────

    @Test
    fun `ListFilesResponse has correct structure`() {
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
        assertEquals("/", dto.path)
        assertEquals(1, dto.entries.size)
        assertEquals("server.properties", dto.entries[0].name)
        assertEquals(1024L, dto.entries[0].sizeBytes)
    }

    @Test
    fun `ReadFileResponse has correct structure`() {
        val dto = ReadFileResponse(
            path = "/server.properties",
            content = "level-name=world\n",
            encoding = "utf-8",
        )
        assertEquals("/server.properties", dto.path)
        assertEquals("level-name=world\n", dto.content)
        assertEquals("utf-8", dto.encoding)
    }

    // ── console STOPPED guard ─────────────────────────────────────────────────

    @Test
    fun `console returns emptyFlow when server is STOPPED`() {
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "STOPPED")
        val result = proxy.console(serverId.toString(), kotlinx.coroutines.flow.emptyFlow())
        // emptyFlow() returns immediately — just verify no exception
        runBlocking {
            result.collect {}
        }
    }
}
