package io.craftpanel.master.routes

import io.craftpanel.master.*
import io.craftpanel.master.auth.*
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.database.schema.*
import io.craftpanel.master.service.*
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class ServersRoutesTest :
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

        lateinit var repos: TestRepositories

        fun Route.configureServersTest(gateway: TestAgentGateway = TestAgentGateway()) {
            repos = TestRepositories()
            val serverRepository = repos.serverRepository
            val networkRepository = NetworkRepositoryImpl()
            val settingsRepository = SettingsRepositoryImpl()
            val modService = ModService(modRepository = repos.modRepository, serverRepository = serverRepository)
            val lifecycle = ContainerLifecycle(
                gateway = gateway,
                modService = modService,
                serverRepository = serverRepository,
                envVarsRepository = repos.envVarsRepository
            )
            val nodeRepository = NodeRepositoryImpl()
            val serverExposure = ServerExposure(
                networkRepository = networkRepository,
                settingsRepository = settingsRepository,
                serverRepository = serverRepository
            )
            val lifecycleService = ServerLifecycleService(
                lifecycle = lifecycle,
                serverRepository = serverRepository,
                serverExposure = serverExposure,
                proxyConfigPatchService = ProxyConfigPatchService(repos.proxyBackendRepository, serverRepository),
                writeFile = { _, _, _ -> }
            )
            val exposureService = ServerExposureService(
                dnsProvider = null,
                lifecycle = lifecycle,
                serverRepository = serverRepository,
                nodeRepository = nodeRepository,
                serverExposure = serverExposure
            )
            serversRoutes(
                ServerService(
                    gateway = gateway,
                    serverRepository = serverRepository,
                    nodeRepository = nodeRepository,
                    networkRepository = networkRepository,
                    userRepository = UserRepositoryImpl(),
                    groupRepository = GroupRepositoryImpl(),
                    settingsRepository = settingsRepository,
                    portRepository = repos.portRepository,
                    envVarsRepository = repos.envVarsRepository,
                    modRepository = repos.modRepository,
                    containerMetricsRepository = repos.containerMetricsRepository,
                    migrationRepository = repos.migrationRepository
                ),
                lifecycleService,
                exposureService,
                serverExposure
            )
        }

        fun createUser(username: String = "admin", email: String = "admin@example.com"): Uuid = transaction {
            Users.insert {
                it[Users.username] = username
                it[Users.email] = email
                it[Users.passwordHash] = Argon2Hasher.hash("hunter2")
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

        fun createNode(hostname: String = "node-1", status: String = "ACTIVE", totalRamMb: Int = 8192, totalCpuShares: Int = 0, portStart: Int = 25565, portEnd: Int = 25600): Uuid = transaction {
            Nodes.insert {
                it[Nodes.hostname] = hostname
                it[Nodes.displayName] = hostname
                it[Nodes.publicIp] = "1.2.3.4"
                it[Nodes.privateIp] = "10.0.0.1"
                it[Nodes.tokenHash] = "aa${
                    hostname.hashCode()
                        .toString(16)
                        .padStart(62, '0')
                }".take(64)
                it[Nodes.status] = status
                it[Nodes.totalRamMb] = totalRamMb
                it[Nodes.totalCpuShares] = totalCpuShares
                it[Nodes.portRangeStart] = portStart
                it[Nodes.portRangeEnd] = portEnd
            }[Nodes.id].let { Uuid.parse(it.toString()) }
        }

        fun createNetwork(name: String = "net-1"): Uuid = transaction {
            ServerNetworks.insert {
                it[ServerNetworks.name] = name
            }[ServerNetworks.id].let { Uuid.parse(it.toString()) }
        }

        fun createServer(
            nodeId: Uuid,
            name: String = "server-${Uuid.random()}",
            networkId: Uuid? = null,
            status: String = "STOPPED",
            memoryMb: Int = 1024,
            port: Int = 25565,
            mcVersion: String = "1.21.4"
        ): Uuid = transaction {
            Servers.insert {
                it[Servers.nodeId] = nodeId
                it[Servers.networkId] = networkId
                it[Servers.name] = name
                it[Servers.displayName] = name
                it[Servers.serverType] = "VANILLA"
                it[Servers.mcVersion] = mcVersion
                it[Servers.hostPort] = port
                it[Servers.memoryMb] = memoryMb
                it[Servers.cpuShares] = 0
                it[Servers.status] = status
            }[Servers.id].let { Uuid.parse(it.toString()) }
        }

        // ── GET /servers ─────────────────────────────────────────────────────────

        test("GET servers returns 401 without token") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val resp = client.get("/api/servers")
                resp.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("GET servers returns empty list for user with no permissions") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                val nodeId = createNode()
                createServer(nodeId, "hidden")
                val resp = client.get("/api/servers") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.OK
                resp.body<List<JsonObject>>().size shouldBe 0
            }
        }

        test("GET servers returns all servers for global viewer") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Viewer")
                val nodeId = createNode()
                createServer(nodeId, "s1")
                createServer(nodeId, "s2")
                val resp = client.get("/api/servers") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.OK
                val body = resp.body<List<JsonObject>>()
                body.size shouldBe 2
                val names = body.map { it["name"]!!.jsonPrimitive.content }
                    .toSet()
                names shouldBe setOf("s1", "s2")
            }
        }

        test("GET servers includes is_migrating field") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Viewer")
                val nodeId = createNode()
                val serverId = createServer(nodeId, "migrating-server")
                transaction {
                    ServerMigrations.insert {
                        it[ServerMigrations.serverId] = serverId
                        it[ServerMigrations.sourceNodeId] = nodeId
                        it[ServerMigrations.targetNodeId] = nodeId
                        it[ServerMigrations.status] = "RUNNING"
                    }
                }
                val resp = client.get("/api/servers") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.OK
                val server = resp.body<List<JsonObject>>()
                    .first { it["name"]!!.jsonPrimitive.content == "migrating-server" }
                server["is_migrating"]!!.jsonPrimitive.content shouldBe "true"
            }
        }

        // ── POST /servers ─────────────────────────────────────────────────────────

        test("POST servers returns 403 without server-create") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Viewer")
                val nodeId = createNode()
                val resp = client.post("/api/servers") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"s","node_id":"$nodeId","server_type":"VANILLA","memory_mb":1024}""")
                }
                resp.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("POST servers creates server and allocates port") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val resp = client.post("/api/servers") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"new-server","display_name":"New Server","node_id":"$nodeId","server_type":"PAPER","memory_mb":2048,"cpu_shares":0}""")
                }
                resp.status shouldBe HttpStatusCode.Created
                val body = resp.body<JsonObject>()
                body["name"]!!.jsonPrimitive.content shouldBe "new-server"
                body["display_name"]!!.jsonPrimitive.content shouldBe "New Server"
                body["server_type"]!!.jsonPrimitive.content shouldBe "PAPER"
                body["host_port"]!!.jsonPrimitive.content.toInt() shouldBe 25565
                body["is_migrating"]!!.jsonPrimitive.content shouldBe "false"
                body["id"] shouldNotBe null

                val portCount = transaction {
                    PortRegistry.selectAll()
                        .where { PortRegistry.nodeId eq nodeId }
                        .count()
                }
                portCount shouldBe 1L
            }
        }

        test("POST servers allocates sequential ports") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode(totalRamMb = 8192)
                repeat(3) { i ->
                    val resp = client.post("/api/servers") {
                        bearerAuth(tokenFor(userId))
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"srv-$i","node_id":"$nodeId","server_type":"VANILLA","memory_mb":512}""")
                    }
                    resp.status shouldBe HttpStatusCode.Created
                    val port = resp.body<JsonObject>()["host_port"]!!.jsonPrimitive.content.toInt()
                    port shouldBe 25565 + i
                }
            }
        }

        test("POST servers returns 422 when node not ACTIVE") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode(status = "PENDING")
                val resp = client.post("/api/servers") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"srv","node_id":"$nodeId","server_type":"VANILLA","memory_mb":1024}""")
                }
                resp.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }

        test("POST servers returns 409 when insufficient RAM") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode(totalRamMb = 2048)
                createServer(nodeId, memoryMb = 1024)
                val resp = client.post("/api/servers") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"big","node_id":"$nodeId","server_type":"VANILLA","memory_mb":1025}""")
                }
                resp.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("POST servers returns 409 on duplicate name") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                createServer(nodeId, "dup-name")
                val resp = client.post("/api/servers") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"dup-name","node_id":"$nodeId","server_type":"VANILLA","memory_mb":512}""")
                }
                resp.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("POST servers returns 422 for invalid node_id") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val resp = client.post("/api/servers") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"srv","node_id":"not-a-uuid","server_type":"VANILLA","memory_mb":512}""")
                }
                resp.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }

        test("POST servers returns 422 for nonexistent network_id") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val fakeNetId = Uuid.random()
                val resp = client.post("/api/servers") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"srv","node_id":"$nodeId","network_id":"$fakeNetId","server_type":"VANILLA","memory_mb":512}""")
                }
                resp.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }

        test("POST servers stores mc_version and itzg_image_tag") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val resp = client.post("/api/servers") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"versioned","node_id":"$nodeId","server_type":"PAPER","mc_version":"1.21.4","itzg_image_tag":"java21","memory_mb":1024}""")
                }
                resp.status shouldBe HttpStatusCode.Created
                val body = resp.body<JsonObject>()
                body["mc_version"]!!.jsonPrimitive.content shouldBe "1.21.4"
                body["itzg_image_tag"]!!.jsonPrimitive.content shouldBe "java21"
            }
        }

        test("POST servers derives stop_command for proxy types") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val resp = client.post("/api/servers") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"proxy-srv","node_id":"$nodeId","server_type":"VELOCITY","memory_mb":512}""")
                }
                resp.status shouldBe HttpStatusCode.Created
                val id = Uuid.parse(resp.body<JsonObject>()["id"]!!.jsonPrimitive.content)
                val row = transaction {
                    Servers.selectAll()
                        .where { Servers.id eq id }
                        .first()
                }
                row[Servers.stopCommand] shouldBe "end"
            }
        }

        test("POST servers sets a default proxy_motd for proxy types") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val resp = client.post("/api/servers") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"proxy-motd-srv","node_id":"$nodeId","server_type":"VELOCITY","memory_mb":512}""")
                }
                resp.status shouldBe HttpStatusCode.Created
                val id = Uuid.parse(resp.body<JsonObject>()["id"]!!.jsonPrimitive.content)
                val row = transaction {
                    Servers.selectAll()
                        .where { Servers.id eq id }
                        .first()
                }
                row[Servers.proxyMotd] shouldBe "Velocity powered by CraftPanel"
            }
        }

        // ── GET /servers/{id} ────────────────────────────────────────────────────

        test("GET server by id returns 404 for unknown") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Viewer")
                val resp = client.get("/api/servers/${Uuid.random()}") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("GET server by id returns full object for viewer") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Viewer")
                val nodeId = createNode()
                val serverId = createServer(nodeId, "detail-srv")
                val resp = client.get("/api/servers/$serverId") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.OK
                val body = resp.body<JsonObject>()
                body["name"]!!.jsonPrimitive.content shouldBe "detail-srv"
                body["is_migrating"] shouldNotBe null
                body["host_port"] shouldNotBe null
                body["created_at"] shouldNotBe null
                body["updated_at"] shouldNotBe null
            }
        }

        test("GET server by id returns 403 for user with no permissions") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                val nodeId = createNode()
                val serverId = createServer(nodeId, "private-srv")
                val resp = client.get("/api/servers/$serverId") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.Forbidden
            }
        }

        // ── PATCH /servers/{id} ──────────────────────────────────────────────────

        test("PATCH server returns 403 without server-configure") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Viewer")
                val nodeId = createNode()
                val serverId = createServer(nodeId)
                val resp = client.patch("/api/servers/$serverId") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"display_name":"New Name"}""")
                }
                resp.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("PATCH server updates display_name") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val serverId = createServer(nodeId, "to-patch")
                val resp = client.patch("/api/servers/$serverId") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"display_name":"Patched Name"}""")
                }
                resp.status shouldBe HttpStatusCode.NoContent
                val row = transaction {
                    Servers.selectAll()
                        .where { Servers.id eq serverId }
                        .first()
                }
                row[Servers.displayName] shouldBe "Patched Name"
            }
        }

        test("PATCH server clears network_id when null") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val netId = createNetwork()
                val serverId = createServer(nodeId, networkId = netId)
                val resp = client.patch("/api/servers/$serverId") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"network_id":""}""")
                }
                resp.status shouldBe HttpStatusCode.NoContent
                val row = transaction {
                    Servers.selectAll()
                        .where { Servers.id eq serverId }
                        .first()
                }
                row[Servers.networkId] shouldBe null
            }
        }

        test("PATCH server skips network_id when key absent") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val netId = createNetwork()
                val serverId = createServer(nodeId, networkId = netId)
                val resp = client.patch("/api/servers/$serverId") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"display_name":"No Network Change"}""")
                }
                resp.status shouldBe HttpStatusCode.NoContent
                val row = transaction {
                    Servers.selectAll()
                        .where { Servers.id eq serverId }
                        .first()
                }
                row[Servers.networkId] shouldBe netId
            }
        }

        test("PATCH server returns 422 for nonexistent network_id") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val serverId = createServer(nodeId)
                val fakeNetId = Uuid.random()
                val resp = client.patch("/api/servers/$serverId") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"network_id":"$fakeNetId"}""")
                }
                resp.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }

        // ── DELETE /servers/{id} ─────────────────────────────────────────────────

        test("DELETE server returns 409 when not STOPPED") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val serverId = createServer(nodeId, status = "HEALTHY")
                val resp = client.delete("/api/servers/$serverId") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("DELETE server removes server and port registry entry") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val serverId = createServer(nodeId, "to-delete", status = "STOPPED")
                transaction {
                    PortRegistry.insert {
                        it[PortRegistry.nodeId] = nodeId
                        it[PortRegistry.port] = 25565
                        it[PortRegistry.protocol] = "TCP"
                        it[PortRegistry.serverId] = serverId
                    }
                }
                val resp = client.delete("/api/servers/$serverId") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.NoContent

                val serverExists = transaction {
                    Servers.selectAll()
                        .where { Servers.id eq serverId }
                        .firstOrNull() != null
                }
                serverExists shouldBe false
                val portExists = transaction {
                    PortRegistry.selectAll()
                        .where { PortRegistry.serverId eq serverId }
                        .firstOrNull() != null
                }
                portExists shouldBe false
            }
        }

        test("DELETE server sends removeContainer with deleteData=true so the agent cleans up files") {
            testApplication {
                val gateway = TestAgentGateway()
                testApp { jwtManager -> configureServersTest(gateway) }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val serverId = createServer(nodeId, "to-delete-with-files", status = "STOPPED")

                val resp = client.delete("/api/servers/$serverId") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.NoContent

                val sentRemove = gateway.sent.map { it.second }
                    .single { it.hasRemoveContainer() }.removeContainer
                sentRemove.serverId shouldBe serverId.toString()
                sentRemove.deleteData shouldBe true
            }
        }

        test("DELETE server returns 404 for unknown") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val resp = client.delete("/api/servers/${Uuid.random()}") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.NotFound
            }
        }

        // ── PATCH /servers/{id}/resources ────────────────────────────────────────

        test("PATCH resources returns 403 without server-resources permission") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Server Admin")
                val nodeId = createNode()
                val serverId = createServer(nodeId)
                val resp = client.patch("/api/servers/$serverId/resources") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"memory_mb":2048,"cpu_shares":0}""")
                }
                resp.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("PATCH resources updates memory and cpu") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode(totalRamMb = 8192)
                val serverId = createServer(nodeId, memoryMb = 1024)
                val resp = client.patch("/api/servers/$serverId/resources") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"memory_mb":3000,"cpu_shares":512}""")
                }
                resp.status shouldBe HttpStatusCode.NoContent
                val row = transaction {
                    Servers.selectAll()
                        .where { Servers.id eq serverId }
                        .first()
                }
                row[Servers.memoryMb] shouldBe 3000
                row[Servers.cpuShares] shouldBe 512
            }
        }

        test("PATCH resources returns 409 when insufficient RAM") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode(totalRamMb = 4096)
                createServer(nodeId, "other", memoryMb = 2048, port = 25566)
                val serverId = createServer(nodeId, "target", memoryMb = 1024)
                val resp = client.patch("/api/servers/$serverId/resources") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"memory_mb":3000,"cpu_shares":0}""")
                }
                resp.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("PATCH resources validates memory_mb positive") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val serverId = createServer(nodeId)
                val resp = client.patch("/api/servers/$serverId/resources") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"memory_mb":0,"cpu_shares":0}""")
                }
                resp.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }

        // ── PATCH /servers/{id}/exposure ─────────────────────────────────────────

        test("PATCH exposure persists values") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val serverId = createServer(nodeId, "expose-me")
                val resp = client.patch("/api/servers/$serverId/exposure") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"exposed_externally":true,"public_subdomain":"myserver"}""")
                }
                resp.status shouldBe HttpStatusCode.NoContent
                val row = transaction {
                    Servers.selectAll()
                        .where { Servers.id eq serverId }
                        .first()
                }
                row[Servers.exposedExternally] shouldBe true
                row[Servers.publicSubdomain] shouldBe "myserver"
            }
        }

        test("PATCH exposure returns 422 when subdomain already taken") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val s1 = createServer(nodeId, "srv-1", port = 25565)
                val s2 = createServer(nodeId, "srv-2", port = 25566)
                client.patch("/api/servers/$s1/exposure") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"exposed_externally":true,"public_subdomain":"clash"}""")
                }
                val resp = client.patch("/api/servers/$s2/exposure") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"exposed_externally":true,"public_subdomain":"clash"}""")
                }
                resp.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }

        test("PATCH exposure returns 403 without server-configure") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Viewer")
                val nodeId = createNode()
                val serverId = createServer(nodeId)
                val resp = client.patch("/api/servers/$serverId/exposure") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"exposed_externally":false}""")
                }
                resp.status shouldBe HttpStatusCode.Forbidden
            }
        }

        // ── POST /servers/{id}/start ─────────────────────────────────────────────

        test("POST start returns 401 without token") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val nodeId = createNode()
                val serverId = createServer(nodeId)
                val resp = client.post("/api/servers/$serverId/start")
                resp.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("POST start returns 403 without server-start permission") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Viewer")
                val nodeId = createNode()
                val serverId = createServer(nodeId)
                val resp = client.post("/api/servers/$serverId/start") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("POST start returns 404 for unknown server") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val resp = client.post("/api/servers/${Uuid.random()}/start") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("POST start returns 409 if server is HEALTHY") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val serverId = createServer(nodeId, status = "HEALTHY")
                val resp = client.post("/api/servers/$serverId/start") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("POST start returns 409 if server is STARTING") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val serverId = createServer(nodeId, status = "STARTING")
                val resp = client.post("/api/servers/$serverId/start") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("POST start returns 502 when agent not connected") {
            testApplication {
                testApp { jwtManager -> configureServersTest(TestAgentGateway(sendResult = false)) }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val serverId = createServer(nodeId, status = "STOPPED")
                val resp = client.post("/api/servers/$serverId/start") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.BadGateway
            }
        }

        test("POST start returns 202 and updates status to STARTING") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val serverId = createServer(nodeId, status = "STOPPED")
                val resp = client.post("/api/servers/$serverId/start") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.Accepted
                val row = transaction {
                    Servers.selectAll()
                        .where { Servers.id eq serverId }
                        .first()
                }
                row[Servers.status] shouldBe "STARTING"
            }
        }

        test("POST start sends single StartContainerCommand with needsRecreate=false") {
            val gw = TestAgentGateway()
            testApplication {
                testApp { jwtManager -> configureServersTest(gw) }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val serverId = createServer(nodeId, status = "STOPPED")
                val resp = client.post("/api/servers/$serverId/start") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.Accepted
                gw.sent.size shouldBe 1
                gw.sent[0].second.hasStartContainer() shouldBe true
                val cmd = gw.sent[0].second.startContainer
                cmd.containerName shouldBe "craftpanel-$serverId"
                cmd.image shouldBe "itzg/minecraft-server:latest"
                cmd.envVarsMap["EULA"] shouldBe "TRUE"
                cmd.needsRecreate shouldBe false
            }
        }

        test("POST start sends StartContainerCommand with needsRecreate=true when server needs recreate") {
            val gw = TestAgentGateway()
            testApplication {
                testApp { jwtManager -> configureServersTest(gw) }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val serverId = createServer(nodeId, status = "STOPPED")
                transaction { Servers.update({ Servers.id eq serverId }) { it[Servers.needsRecreate] = true } }
                val resp = client.post("/api/servers/$serverId/start") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.Accepted
                gw.sent.size shouldBe 1
                gw.sent[0].second.hasStartContainer() shouldBe true
                gw.sent[0].second.startContainer.needsRecreate shouldBe true
            }
        }

        // ── POST /servers/{id}/stop ──────────────────────────────────────────────

        test("POST stop returns 403 without server-stop permission") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Viewer")
                val nodeId = createNode()
                val serverId = createServer(nodeId, status = "HEALTHY")
                val resp = client.post("/api/servers/$serverId/stop") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("POST stop returns 409 if server is already STOPPED") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val serverId = createServer(nodeId, status = "STOPPED")
                val resp = client.post("/api/servers/$serverId/stop") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("POST stop returns 502 when agent not connected") {
            testApplication {
                testApp { jwtManager -> configureServersTest(TestAgentGateway(sendResult = false)) }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val serverId = createServer(nodeId, status = "HEALTHY")
                val resp = client.post("/api/servers/$serverId/stop") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.BadGateway
            }
        }

        test("POST stop returns 202 and sends StopContainerCommand") {
            val gw = TestAgentGateway()
            testApplication {
                testApp { jwtManager -> configureServersTest(gw) }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val serverId = createServer(nodeId, status = "HEALTHY")
                val resp = client.post("/api/servers/$serverId/stop") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.Accepted
                gw.sent.size shouldBe 1
                gw.sent[0].second.hasStopContainer() shouldBe true
                gw.sent[0].second.stopContainer.containerName shouldBe "craftpanel-$serverId"
            }
        }

        // ── POST /servers/{id}/force-stop ──────────────────────────────────────

        test("POST force-stop returns 403 without server-force_stop permission") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Viewer")
                val nodeId = createNode()
                val serverId = createServer(nodeId, status = "HEALTHY")
                val resp = client.post("/api/servers/$serverId/force-stop") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("POST force-stop returns 409 if server is already STOPPED") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val serverId = createServer(nodeId, status = "STOPPED")
                val resp = client.post("/api/servers/$serverId/force-stop") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("POST force-stop returns 202 and sends StopContainerCommand with force=true") {
            val gw = TestAgentGateway()
            testApplication {
                testApp { jwtManager -> configureServersTest(gw) }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val serverId = createServer(nodeId, status = "STOPPING")
                val resp = client.post("/api/servers/$serverId/force-stop") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.Accepted
                gw.sent.size shouldBe 1
                gw.sent[0].second.hasStopContainer() shouldBe true
                gw.sent[0].second.stopContainer.force shouldBe true
            }
        }

        // ── POST /servers/{id}/restart ───────────────────────────────────────────

        test("POST restart returns 403 without server-restart permission") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Viewer")
                val nodeId = createNode()
                val serverId = createServer(nodeId, status = "HEALTHY")
                val resp = client.post("/api/servers/$serverId/restart") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("POST restart returns 502 when agent not connected") {
            testApplication {
                testApp { jwtManager -> configureServersTest(TestAgentGateway(sendResult = false)) }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val serverId = createServer(nodeId, status = "HEALTHY")
                val resp = client.post("/api/servers/$serverId/restart") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.BadGateway
            }
        }

        test("POST restart returns 202 and sends StopContainerCommand then StartContainerCommand") {
            val gw = TestAgentGateway()
            testApplication {
                testApp { jwtManager -> configureServersTest(gw) }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val serverId = createServer(nodeId, status = "HEALTHY")
                val resp = client.post("/api/servers/$serverId/restart") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.Accepted
                gw.sent.size shouldBe 2
                gw.sent[0].second.hasStopContainer() shouldBe true
                gw.sent[0].second.stopContainer.containerName shouldBe "craftpanel-$serverId"
                gw.sent[1].second.hasStartContainer() shouldBe true
                gw.sent[1].second.startContainer.containerName shouldBe "craftpanel-$serverId"
            }
        }

        // ── POST /servers/{id}/clone ──────────────────────────────────────────────

        test("POST clone requires server.create permission") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Viewer")
                val nodeId = createNode()
                val sourceId = createServer(nodeId, "source")
                val resp = client.post("/api/servers/$sourceId/clone") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"clone-1"}""")
                }
                resp.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("POST clone copies config, env vars and mods to a new unique server") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val sourceId = createServer(nodeId, "source", memoryMb = 2048, mcVersion = "1.21.4")
                transaction {
                    repos.envVarsRepository.replaceEnvVars(
                        sourceId,
                        listOf(EnvVarRow("EULA", "TRUE"), EnvVarRow("LEVEL", "world"))
                    )
                    repos.modRepository.createMod(
                        serverId = sourceId,
                        modrinthProjectId = "fabric-api",
                        displayName = "Fabric API",
                        pinStrategy = "LATEST",
                        pinnedVersionId = null,
                        installedVersionId = null
                    )
                }

                val resp = client.post("/api/servers/$sourceId/clone") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"clone-1","display_name":"Cloned","description":"a copy"}""")
                }
                resp.status shouldBe HttpStatusCode.Created
                val body = resp.body<JsonObject>()
                body["name"]!!.jsonPrimitive.content shouldBe "clone-1"
                body["display_name"]!!.jsonPrimitive.content shouldBe "Cloned"
                body["description"]!!.jsonPrimitive.content shouldBe "a copy"
                body["server_type"]!!.jsonPrimitive.content shouldBe "VANILLA"
                body["mc_version"]!!.jsonPrimitive.content shouldBe "1.21.4"
                body["memory_mb"]!!.jsonPrimitive.content.toInt() shouldBe 2048

                val newId = Uuid.parse(body["id"]!!.jsonPrimitive.content)
                newId shouldNotBe sourceId
                transaction {
                    Servers.selectAll()
                        .where { Servers.name eq "clone-1" }
                        .count() shouldBe 1L
                    val envVars = repos.envVarsRepository.getEnvVars(newId)
                        .associate { it.key to it.value }
                    envVars["EULA"] shouldBe "TRUE"
                    envVars["LEVEL"] shouldBe "world"
                    repos.modRepository.listMods(newId).size shouldBe 1
                }
            }
        }

        test("POST clone returns 409 when name is taken") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val nodeId = createNode()
                val sourceId = createServer(nodeId, "source")
                createServer(nodeId, "taken")
                val resp = client.post("/api/servers/$sourceId/clone") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"taken"}""")
                }
                resp.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("POST clone returns 404 when source missing") {
            testApplication {
                testApp { jwtManager -> configureServersTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                createNode()
                val missing = Uuid.random()
                val resp = client.post("/api/servers/$missing/clone") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"clone-x"}""")
                }
                resp.status shouldBe HttpStatusCode.NotFound
            }
        }
    })
