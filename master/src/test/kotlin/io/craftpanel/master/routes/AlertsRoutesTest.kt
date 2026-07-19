package io.craftpanel.master.routes

import io.craftpanel.master.*
import io.craftpanel.master.auth.*
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.database.schema.*
import io.craftpanel.master.service.AlertService
import io.craftpanel.master.service.repo.impl.AlertRepositoryImpl
import io.craftpanel.master.service.repo.impl.NodeRepositoryImpl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.uuid.Uuid

class AlertsRoutesTest :
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

        fun Route.configureAlertsTest() {
            val repos = TestRepositories()
            alertsRoutes(AlertService(AlertRepositoryImpl(), NodeRepositoryImpl(), repos.serverRepository))
        }

        fun createUser(email: String = "admin@example.com"): Uuid = transaction {
            Users.insert {
                it[Users.username] = email.substringBefore("@")
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

        fun tokenFor(userId: Uuid): String = jwtManager.generate(TokenClaims(userId = userId, name = "admin", email = "admin@example.com", groups = emptyList()))

        fun createNode(hostname: String = "node-1"): Uuid = transaction {
            Nodes.insert {
                it[Nodes.hostname] = hostname
                it[Nodes.displayName] = hostname
                it[Nodes.publicIp] = "1.2.3.4"
                it[Nodes.privateIp] = "10.0.0.1"
                it[Nodes.tokenHash] = "a".repeat(64)
                it[Nodes.status] = "ACTIVE"
                it[Nodes.totalRamMb] = 8192
                it[Nodes.totalCpuShares] = 1024
            }[Nodes.id].let { Uuid.parse(it.toString()) }
        }

        // ── List thresholds ───────────────────────────────────────────────────────

        test("list thresholds requires system-settings") {
            testApplication {
                testApp { _ -> configureAlertsTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Operator")
                val token = tokenFor(userId)
                val res = client.get("/api/alerts/thresholds") { header("Authorization", "Bearer $token") }
                res.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("list thresholds returns empty list when none exist") {
            testApplication {
                testApp { _ -> configureAlertsTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val token = tokenFor(userId)
                val res = client.get("/api/alerts/thresholds") { header("Authorization", "Bearer $token") }
                res.status shouldBe HttpStatusCode.OK
                val body = res.body<JsonObject>()
                body["thresholds"]!!.jsonArray.size shouldBe 0
            }
        }

        // ── Create threshold ──────────────────────────────────────────────────────

        test("create threshold with numeric value succeeds") {
            testApplication {
                testApp { _ -> configureAlertsTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val token = tokenFor(userId)
                val nodeId = createNode()

                val res = client.post("/api/alerts/thresholds") {
                    header("Authorization", "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"scope_type":"NODE","scope_id":"$nodeId","metric":"cpu_percent","threshold_value":80.0}""")
                }
                res.status shouldBe HttpStatusCode.Created
                val body = res.body<JsonObject>()
                body["scope_type"]!!.jsonPrimitive.content shouldBe "NODE"
                body["metric"]!!.jsonPrimitive.content shouldBe "cpu_percent"
                body["threshold_value"]!!.jsonPrimitive.content.toDouble() shouldBe 80.0
            }
        }

        test("create threshold with state value succeeds") {
            testApplication {
                testApp { _ -> configureAlertsTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val token = tokenFor(userId)
                val nodeId = createNode()

                val res = client.post("/api/alerts/thresholds") {
                    header("Authorization", "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"scope_type":"NODE","scope_id":"$nodeId","metric":"server_health","threshold_state":"UNHEALTHY"}""")
                }
                res.status shouldBe HttpStatusCode.Created
                val body = res.body<JsonObject>()
                body["threshold_state"]!!.jsonPrimitive.content shouldBe "UNHEALTHY"
            }
        }

        test("create threshold rejects both value and state") {
            testApplication {
                testApp { _ -> configureAlertsTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val token = tokenFor(userId)
                val nodeId = createNode()

                val res = client.post("/api/alerts/thresholds") {
                    header("Authorization", "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"scope_type":"NODE","scope_id":"$nodeId","metric":"cpu_percent","threshold_value":80.0,"threshold_state":"UNHEALTHY"}""")
                }
                res.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }

        test("create threshold rejects neither value nor state") {
            testApplication {
                testApp { _ -> configureAlertsTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val token = tokenFor(userId)
                val nodeId = createNode()

                val res = client.post("/api/alerts/thresholds") {
                    header("Authorization", "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"scope_type":"NODE","scope_id":"$nodeId","metric":"cpu_percent"}""")
                }
                res.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }

        // ── Delete threshold ──────────────────────────────────────────────────────

        test("delete threshold removes it and its events") {
            testApplication {
                testApp { _ -> configureAlertsTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val token = tokenFor(userId)
                val nodeId = createNode()

                val thresholdId = transaction {
                    AlertThresholds.insert {
                        it[AlertThresholds.scopeType] = "NODE"
                        it[AlertThresholds.scopeId] = nodeId
                        it[AlertThresholds.metric] = "cpu_percent"
                        it[AlertThresholds.thresholdValue] = 90.0
                    }[AlertThresholds.id]
                }

                transaction {
                    AlertEvents.insert {
                        it[AlertEvents.thresholdId] = thresholdId
                        it[AlertEvents.message] = "test event"
                        it[AlertEvents.firedAt] = Clock.System.now()
                            .toLocalDateTime(TimeZone.UTC)
                    }
                }

                val res = client.delete("/api/alerts/thresholds/$thresholdId") {
                    header("Authorization", "Bearer $token")
                }
                res.status shouldBe HttpStatusCode.NoContent

                transaction {
                    AlertThresholds.selectAll()
                        .where { AlertThresholds.id eq thresholdId }
                        .count() shouldBe 0
                    AlertEvents.selectAll()
                        .where { AlertEvents.thresholdId eq thresholdId }
                        .count() shouldBe 0
                }
            }
        }

        test("delete nonexistent threshold returns 404") {
            testApplication {
                testApp { _ -> configureAlertsTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val token = tokenFor(userId)

                val res = client.delete("/api/alerts/thresholds/${Uuid.random()}") {
                    header("Authorization", "Bearer $token")
                }
                res.status shouldBe HttpStatusCode.NotFound
            }
        }

        // ── List events ───────────────────────────────────────────────────────────

        test("list events returns all events") {
            testApplication {
                testApp { _ -> configureAlertsTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val token = tokenFor(userId)
                val nodeId = createNode()
                val now = Clock.System.now()
                    .toLocalDateTime(TimeZone.UTC)

                val thresholdId = transaction {
                    AlertThresholds.insert {
                        it[AlertThresholds.scopeType] = "NODE"
                        it[AlertThresholds.scopeId] = nodeId
                        it[AlertThresholds.metric] = "ram_percent"
                        it[AlertThresholds.thresholdValue] = 85.0
                    }[AlertThresholds.id]
                }

                transaction {
                    AlertEvents.insert {
                        it[AlertEvents.thresholdId] = thresholdId
                        it[AlertEvents.message] = "Node node-1: ram_percent at 90.0%"
                        it[AlertEvents.firedAt] = now
                        it[AlertEvents.resolvedAt] = now
                    }
                    AlertEvents.insert {
                        it[AlertEvents.thresholdId] = thresholdId
                        it[AlertEvents.message] = "Node node-1: ram_percent at 88.0%"
                        it[AlertEvents.firedAt] = now
                    }
                }

                val res = client.get("/api/alerts/events") { header("Authorization", "Bearer $token") }
                res.status shouldBe HttpStatusCode.OK
                val events = res.body<JsonObject>()["events"]!!.jsonArray
                events.size shouldBe 2
            }
        }

        test("list events active_only filters resolved events") {
            testApplication {
                testApp { _ -> configureAlertsTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val token = tokenFor(userId)
                val nodeId = createNode()
                val now = Clock.System.now()
                    .toLocalDateTime(TimeZone.UTC)

                val thresholdId = transaction {
                    AlertThresholds.insert {
                        it[AlertThresholds.scopeType] = "NODE"
                        it[AlertThresholds.scopeId] = nodeId
                        it[AlertThresholds.metric] = "cpu_percent"
                        it[AlertThresholds.thresholdValue] = 80.0
                    }[AlertThresholds.id]
                }

                transaction {
                    AlertEvents.insert {
                        it[AlertEvents.thresholdId] = thresholdId
                        it[AlertEvents.message] = "resolved"
                        it[AlertEvents.firedAt] = now
                        it[AlertEvents.resolvedAt] = now
                    }
                    AlertEvents.insert {
                        it[AlertEvents.thresholdId] = thresholdId
                        it[AlertEvents.message] = "open"
                        it[AlertEvents.firedAt] = now
                    }
                }

                val res = client.get("/api/alerts/events?active_only=true") { header("Authorization", "Bearer $token") }
                res.status shouldBe HttpStatusCode.OK
                val events = res.body<JsonObject>()["events"]!!.jsonArray
                events.size shouldBe 1
                events[0].jsonObject["message"]!!.jsonPrimitive.content shouldBe "open"
            }
        }
    })
