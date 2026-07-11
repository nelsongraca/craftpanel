package io.craftpanel.master.routes

import io.craftpanel.master.TestAgentGateway
import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.auth.Argon2Hasher
import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.auth.TokenClaims
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.database.schema.*
import io.craftpanel.master.jsonClient
import io.craftpanel.master.service.*
import io.craftpanel.master.service.repo.NodeRepositoryImpl
import io.craftpanel.master.testApp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.routing.Route
import io.ktor.server.testing.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.uuid.Uuid

class NodesRoutesTest :
    FunSpec({
        val jwtConfig = JwtConfig(
            secret = "test-secret-that-is-at-least-32-characters!!",
            issuer = "craftpanel-test",
            audience = "craftpanel-test",
            expirySeconds = 900
        )
        val jwtManager = JwtManager(jwtConfig)

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
        }

        val repos = TestRepositories()

        fun Route.configureNodesTest(gateway: TestAgentGateway = TestAgentGateway()) {
            nodesRoutes(NodeService(gateway, NodeRepositoryImpl(), repos.serverRepository))
        }

        fun createUser(username: String = "admin", email: String = "admin@example.com", password: String = "hunter2"): Uuid = transaction {
            Users.insert {
                it[Users.username] = username
                it[Users.email] = email
                it[Users.passwordHash] = Argon2Hasher.hash(password)
                it[Users.isActive] = true
            }[Users.id].let { Uuid.parse(it.toString()) }
        }

        fun assignGlobalGroup(userId: Uuid, groupName: String) = transaction {
            val groupId = Groups.selectAll()
                .where { Groups.name eq groupName }
                .first()[Groups.id]
            UserGroupAssignments.insert {
                it[UserGroupAssignments.userId] = userId
                it[UserGroupAssignments.groupId] = groupId
                it[UserGroupAssignments.scopeType] = "GLOBAL"
            }
        }

        fun tokenFor(userId: Uuid, username: String = "admin"): String = jwtManager.generate(TokenClaims(userId = userId, name = username, email = "$username@example.com", groups = emptyList()))

        fun createNode(
            hostname: String = "node-1",
            status: String = "PENDING",
            totalRamMb: Int = 8192,
            totalCpuShares: Int = 1024,
            tokenHash: String = "aabbcc${
                hostname.hashCode()
                    .toString(16)
                    .padStart(58, '0')
            }"
        ): Uuid = transaction {
            Nodes.insert {
                it[Nodes.hostname] = hostname
                it[Nodes.displayName] = hostname
                it[Nodes.publicIp] = "1.2.3.4"
                it[Nodes.privateIp] = "10.0.0.1"
                it[Nodes.tokenHash] = tokenHash.take(64)
                    .padEnd(64, '0')
                it[Nodes.status] = status
                it[Nodes.totalRamMb] = totalRamMb
                it[Nodes.totalCpuShares] = totalCpuShares
            }[Nodes.id].let { Uuid.parse(it.toString()) }
        }

        fun createServer(nodeId: Uuid, memoryMb: Int = 1024, cpuShares: Int = 256): Uuid = transaction {
            Servers.insert {
                it[Servers.nodeId] = nodeId
                it[Servers.name] = "server-${Uuid.random()}"
                it[Servers.hostPort] = 25565
                it[Servers.memoryMb] = memoryMb
                it[Servers.cpuShares] = cpuShares
            }[Servers.id].let { Uuid.parse(it.toString()) }
        }

        fun createNodeMetric(nodeId: Uuid, cpuPercent: Double = 42.0, ramUsedMb: Int = 2048) = transaction {
            val now = Clock.System.now()
                .toLocalDateTime(TimeZone.UTC)
            NodeMetrics.insert {
                it[NodeMetrics.nodeId] = nodeId
                it[NodeMetrics.recordedAt] = now
                it[NodeMetrics.cpuPercent] = cpuPercent
                it[NodeMetrics.ramUsedMb] = ramUsedMb
                it[NodeMetrics.ramTotalMb] = 8192
                it[NodeMetrics.netInBytes] = 1024L
                it[NodeMetrics.netOutBytes] = 512L
                it[NodeMetrics.diskUsedBytes] = 10_000_000L
                it[NodeMetrics.diskTotalBytes] = 50_000_000L
            }
        }

        // -------------------------------------------------------------------------
        // authentication / authorization
        // -------------------------------------------------------------------------

        test("GET nodes without JWT returns 401") {
            testApplication {
                testApp { _ -> configureNodesTest() }

                client.get("/api/nodes").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("GET nodes without system_nodes permission returns 403") {
            testApplication {
                testApp { _ -> configureNodesTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Viewer")

                val response = client.get("/api/nodes") { bearerAuth(tokenFor(userId)) }

                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("GET nodes with system_nodes permission returns 200") {
            testApplication {
                testApp { _ -> configureNodesTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")

                client.get("/api/nodes") { bearerAuth(tokenFor(userId)) }.status shouldBe HttpStatusCode.OK
            }
        }

        // -------------------------------------------------------------------------
        // GET /nodes
        // -------------------------------------------------------------------------

        test("GET nodes returns empty list when no nodes exist") {
            testApplication {
                testApp { _ -> configureNodesTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val client = jsonClient()

                val body = client.get("/api/nodes") { bearerAuth(tokenFor(userId)) }
                    .body<List<JsonObject>>()

                body.isEmpty() shouldBe true
            }
        }

        test("GET nodes returns node with snake_case fields") {
            testApplication {
                testApp { _ -> configureNodesTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                createNode(hostname = "alpha")
                val client = jsonClient()

                val body = client.get("/api/nodes") { bearerAuth(tokenFor(userId)) }
                    .body<List<JsonObject>>()

                body.size shouldBe 1
                val node = body[0]
                node["id"] shouldNotBe null
                node["display_name"] shouldNotBe null
                node["public_ip"] shouldNotBe null
                node["private_ip"] shouldNotBe null
                node["total_ram_mb"] shouldNotBe null
                node["total_cpu_shares"] shouldNotBe null
                node["allocated_ram_mb"] shouldNotBe null
                node["allocated_cpu_shares"] shouldNotBe null
                node["port_range_start"] shouldNotBe null
                node["port_range_end"] shouldNotBe null
                node["created_at"] shouldNotBe null
                node["display_name"]!!.jsonPrimitive.content shouldBe "alpha"
                node["status"]!!.jsonPrimitive.content shouldBe "PENDING"
            }
        }

        test("GET nodes computes allocated_ram_mb from servers on that node") {
            testApplication {
                testApp { _ -> configureNodesTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                createServer(nodeId, memoryMb = 1024)
                createServer(nodeId, memoryMb = 2048)
                val client = jsonClient()

                val body = client.get("/api/nodes") { bearerAuth(tokenFor(userId)) }
                    .body<List<JsonObject>>()

                body.size shouldBe 1
                body[0]["allocated_ram_mb"]!!.jsonPrimitive.content.toInt() shouldBe 3072
                body[0]["allocated_cpu_shares"]!!.jsonPrimitive.content.toInt() shouldBe 512
            }
        }

        // -------------------------------------------------------------------------
        // GET /nodes/{id}
        // -------------------------------------------------------------------------

        test("GET node by id returns 404 for unknown node") {
            testApplication {
                testApp { _ -> configureNodesTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")

                val response = client.get("/api/nodes/${Uuid.random()}") { bearerAuth(tokenFor(userId)) }

                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("GET node by id returns 400 for invalid UUID") {
            testApplication {
                testApp { _ -> configureNodesTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")

                val response = client.get("/api/nodes/not-a-uuid") { bearerAuth(tokenFor(userId)) }

                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("GET node by id returns correct node") {
            testApplication {
                testApp { _ -> configureNodesTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode(hostname = "beta", totalRamMb = 4096)
                val client = jsonClient()

                val body = client.get("/api/nodes/$nodeId") { bearerAuth(tokenFor(userId)) }
                    .body<JsonObject>()

                body["id"]!!.jsonPrimitive.content shouldBe nodeId.toString()
                body["display_name"]!!.jsonPrimitive.content shouldBe "beta"
                body["total_ram_mb"]!!.jsonPrimitive.content.toInt() shouldBe 4096
            }
        }

        // -------------------------------------------------------------------------
        // POST /nodes/{id}/trust
        // -------------------------------------------------------------------------

        test("trust sets node status to ACTIVE") {
            testApplication {
                testApp { _ -> configureNodesTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode(status = "PENDING")

                val response = client.post("/api/nodes/$nodeId/trust") { bearerAuth(tokenFor(userId)) }

                response.status shouldBe HttpStatusCode.NoContent
                val status = transaction {
                    Nodes.selectAll()
                        .where { Nodes.id eq nodeId }
                        .first()[Nodes.status]
                }
                status shouldBe "ACTIVE"
            }
        }

        test("trust returns 404 for unknown node") {
            testApplication {
                testApp { _ -> configureNodesTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")

                client.post("/api/nodes/${Uuid.random()}/trust") { bearerAuth(tokenFor(userId)) }.status shouldBe HttpStatusCode.NotFound
            }
        }

        // -------------------------------------------------------------------------
        // POST /nodes/{id}/reject
        // -------------------------------------------------------------------------

        test("reject sets node status to REJECTED") {
            testApplication {
                testApp { _ -> configureNodesTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode(status = "PENDING")

                val response = client.post("/api/nodes/$nodeId/reject") { bearerAuth(tokenFor(userId)) }

                response.status shouldBe HttpStatusCode.NoContent
                val status = transaction {
                    Nodes.selectAll()
                        .where { Nodes.id eq nodeId }
                        .first()[Nodes.status]
                }
                status shouldBe "REJECTED"
            }
        }

        test("reject returns 404 for unknown node") {
            testApplication {
                testApp { _ -> configureNodesTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")

                client.post("/api/nodes/${Uuid.random()}/reject") { bearerAuth(tokenFor(userId)) }.status shouldBe HttpStatusCode.NotFound
            }
        }

        // -------------------------------------------------------------------------
        // POST /nodes/{id}/token/rotate
        // -------------------------------------------------------------------------

        test("token rotate returns new node_key") {
            testApplication {
                testApp { _ -> configureNodesTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val client = jsonClient()

                val response = client.post("/api/nodes/$nodeId/token/rotate") { bearerAuth(tokenFor(userId)) }

                response.status shouldBe HttpStatusCode.OK
                val body = response.body<JsonObject>()
                body["node_key"] shouldNotBe null
                body["node_key"]!!.jsonPrimitive.content.isNotBlank() shouldBe true
            }
        }

        test("token rotate changes the stored token hash") {
            testApplication {
                testApp { _ -> configureNodesTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val tokenHash = "a".repeat(64)
                val nodeId = createNode(tokenHash = tokenHash)
                val client = jsonClient()

                client.post("/api/nodes/$nodeId/token/rotate") { bearerAuth(tokenFor(userId)) }

                val newHash = transaction {
                    Nodes.selectAll()
                        .where { Nodes.id eq nodeId }
                        .first()[Nodes.tokenHash]
                }
                newHash shouldNotBe tokenHash
            }
        }

        test("token rotate returns 404 for unknown node") {
            testApplication {
                testApp { _ -> configureNodesTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")

                client.post("/api/nodes/${Uuid.random()}/token/rotate") { bearerAuth(tokenFor(userId)) }.status shouldBe HttpStatusCode.NotFound
            }
        }

        // -------------------------------------------------------------------------
        // POST /nodes/{id}/shutdown
        // -------------------------------------------------------------------------

        test("shutdown returns 502 when agent not connected") {
            testApplication {
                testApp { _ -> configureNodesTest(TestAgentGateway(sendResult = false)) }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()

                client.post("/api/nodes/$nodeId/shutdown") { bearerAuth(tokenFor(userId)) }.status shouldBe HttpStatusCode.BadGateway
            }
        }

        test("shutdown returns 202 when agent is connected") {
            testApplication {
                testApp { _ -> configureNodesTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()

                client.post("/api/nodes/$nodeId/shutdown") { bearerAuth(tokenFor(userId)) }.status shouldBe HttpStatusCode.Accepted
            }
        }

        test("shutdown sends message to the correct node id") {
            testApplication {
                val gw = TestAgentGateway()
                testApp { _ -> configureNodesTest(gw) }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()

                client.post("/api/nodes/$nodeId/shutdown") { bearerAuth(tokenFor(userId)) }

                gw.sent.firstOrNull()?.first shouldBe nodeId.toString()
            }
        }

        test("shutdown returns 404 for unknown node") {
            testApplication {
                testApp { _ -> configureNodesTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")

                client.post("/api/nodes/${Uuid.random()}/shutdown") { bearerAuth(tokenFor(userId)) }.status shouldBe HttpStatusCode.NotFound
            }
        }

        // -------------------------------------------------------------------------
        // PATCH /nodes/{id}
        // -------------------------------------------------------------------------

        test("patch updates display_name") {
            testApplication {
                testApp { _ -> configureNodesTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode(hostname = "old-name")
                val client = jsonClient()

                val response = client.patch("/api/nodes/$nodeId") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody(PatchNodeRequest(displayName = "new-name"))
                }

                response.status shouldBe HttpStatusCode.NoContent
                val name = transaction {
                    Nodes.selectAll()
                        .where { Nodes.id eq nodeId }
                        .first()[Nodes.displayName]
                }
                name shouldBe "new-name"
            }
        }

        test("patch validates port range start must be less than end") {
            testApplication {
                testApp { _ -> configureNodesTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val client = jsonClient()

                val response = client.patch("/api/nodes/$nodeId") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody(PatchNodeRequest(portRangeStart = 25600, portRangeEnd = 25565))
                }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }

        test("patch validates range using current db value for unspecified field") {
            testApplication {
                testApp { _ -> configureNodesTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode() // defaults: start=25570, end=26070
                val client = jsonClient()

                // setting start to 27000 would exceed the current end of 26070 — should fail
                val response = client.patch("/api/nodes/$nodeId") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody(PatchNodeRequest(portRangeStart = 27000))
                }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }

        test("patch returns 404 for unknown node") {
            testApplication {
                testApp { _ -> configureNodesTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val client = jsonClient()

                val response = client.patch("/api/nodes/${Uuid.random()}") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody(PatchNodeRequest(displayName = "x"))
                }

                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        // -------------------------------------------------------------------------
        // DELETE /nodes/{id}
        // -------------------------------------------------------------------------

        test("delete sets node status to DECOMMISSIONED") {
            testApplication {
                testApp { _ -> configureNodesTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode(status = "ACTIVE")

                val response = client.delete("/api/nodes/$nodeId") { bearerAuth(tokenFor(userId)) }

                response.status shouldBe HttpStatusCode.NoContent
                val status = transaction {
                    Nodes.selectAll()
                        .where { Nodes.id eq nodeId }
                        .first()[Nodes.status]
                }
                status shouldBe "DECOMMISSIONED"
            }
        }

        test("delete returns 404 for unknown node") {
            testApplication {
                testApp { _ -> configureNodesTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")

                client.delete("/api/nodes/${Uuid.random()}") { bearerAuth(tokenFor(userId)) }.status shouldBe HttpStatusCode.NotFound
            }
        }

        // -------------------------------------------------------------------------
        // GET /nodes/{id}/metrics
        // -------------------------------------------------------------------------

        test("metrics returns 404 for unknown node") {
            testApplication {
                testApp { _ -> configureNodesTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")

                client.get("/api/nodes/${Uuid.random()}/metrics") { bearerAuth(tokenFor(userId)) }.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("metrics returns empty columnar arrays when no data") {
            testApplication {
                testApp { _ -> configureNodesTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val client = jsonClient()

                val body = client.get("/api/nodes/$nodeId/metrics") { bearerAuth(tokenFor(userId)) }
                    .body<JsonObject>()

                body["timestamps"]!!.jsonArray.isEmpty() shouldBe true
                body["cpu_percent"]!!.jsonArray.isEmpty() shouldBe true
                body["ram_used_mb"]!!.jsonArray.isEmpty() shouldBe true
            }
        }

        test("metrics returns columnar data for recorded metrics") {
            testApplication {
                testApp { _ -> configureNodesTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                createNodeMetric(nodeId, cpuPercent = 55.0, ramUsedMb = 3000)
                createNodeMetric(nodeId, cpuPercent = 60.0, ramUsedMb = 3500)
                val client = jsonClient()

                val body = client.get("/api/nodes/$nodeId/metrics") { bearerAuth(tokenFor(userId)) }
                    .body<JsonObject>()

                body["timestamps"]!!.jsonArray.size shouldBe 2
                body["cpu_percent"]!!.jsonArray.size shouldBe 2
                body["ram_used_mb"]!!.jsonArray.size shouldBe 2
                body["net_in_bytes"]!!.jsonArray.size shouldBe 2
                body["disk_used_bytes"]!!.jsonArray.size shouldBe 2
            }
        }

        test("metrics limit parameter caps the number of returned rows") {
            testApplication {
                testApp { _ -> configureNodesTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                repeat(10) { createNodeMetric(nodeId) }
                val client = jsonClient()

                val body = client.get("/api/nodes/$nodeId/metrics?limit=5") { bearerAuth(tokenFor(userId)) }
                    .body<JsonObject>()

                body["timestamps"]!!.jsonArray.size shouldBe 5
            }
        }
    })
