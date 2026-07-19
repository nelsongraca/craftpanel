package io.craftpanel.master.routes

import io.craftpanel.master.*
import io.craftpanel.master.auth.*
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.database.schema.*
import io.craftpanel.master.service.AssignmentService
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
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class AssignmentsRoutesTest :
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

        fun Route.configureAssignmentsTest() {
            val repos = TestRepositories()
            assignmentsRoutes(
                AssignmentService(
                    userRepository = UserRepositoryImpl(),
                    groupRepository = GroupRepositoryImpl(),
                    serverRepository = repos.serverRepository,
                    networkRepository = NetworkRepositoryImpl()
                )
            )
        }

        fun createUser(username: String = "admin", email: String = "admin@example.com"): Uuid = transaction {
            Users.insert {
                it[Users.username] = username
                it[Users.email] = email
                it[Users.passwordHash] = Argon2Hasher.hash("pass")
            }[Users.id].let { Uuid.parse(it.toString()) }
        }

        fun assignGlobalGroup(userId: Uuid, groupName: String): Uuid = transaction {
            val groupId = Groups.selectAll()
                .where { Groups.name eq groupName }
                .first()[Groups.id]
            UserGroupAssignments.insert {
                it[UserGroupAssignments.userId] = userId
                it[UserGroupAssignments.groupId] = groupId
                it[UserGroupAssignments.scopeType] = "GLOBAL"
            }[UserGroupAssignments.id].let { Uuid.parse(it.toString()) }
        }

        fun groupId(name: String): Uuid = transaction {
            Groups.selectAll()
                .where { Groups.name eq name }
                .first()[Groups.id].let { Uuid.parse(it.toString()) }
        }

        fun tokenFor(userId: Uuid): String = jwtManager.generate(TokenClaims(userId = userId, name = "admin", email = "admin@example.com", groups = emptyList()))

        // ── GET /api/users/{userId}/assignments ───────────────────────────────────

        test("listUserAssignments returns 403 without permission") {
            testApplication {
                testApp { _ -> configureAssignmentsTest() }
                val userId = createUser()
                val targetId = createUser(username = "target", email = "target@example.com")

                client.get("/api/users/$targetId/assignments") { bearerAuth(tokenFor(userId)) }
                    .status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("listUserAssignments returns 404 for unknown user") {
            testApplication {
                testApp { _ -> configureAssignmentsTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")

                client.get("/api/users/${Uuid.random()}/assignments") { bearerAuth(tokenFor(userId)) }
                    .status shouldBe HttpStatusCode.NotFound
            }
        }

        test("listUserAssignments returns assignments for user") {
            testApplication {
                testApp { _ -> configureAssignmentsTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val targetId = createUser(username = "target", email = "target@example.com")
                assignGlobalGroup(targetId, "Viewer")
                val client = jsonClient()

                val body = client.get("/api/users/$targetId/assignments") { bearerAuth(tokenFor(userId)) }
                    .body<JsonObject>()
                val assignments = body["assignments"]!!.jsonArray
                assignments.size shouldBe 1
                assignments[0].jsonObject["scope_type"]!!.jsonPrimitive.content shouldBe "GLOBAL"
            }
        }

        // ── POST /api/users/{userId}/assignments ──────────────────────────────────

        test("createAssignment returns 403 without permission") {
            testApplication {
                testApp { _ -> configureAssignmentsTest() }
                val userId = createUser()
                val response = client.post("/api/users/$userId/assignments") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"group_id":"${Uuid.random()}","scope_type":"GLOBAL"}""")
                }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("createAssignment creates GLOBAL assignment") {
            testApplication {
                testApp { _ -> configureAssignmentsTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val targetId = createUser(username = "target", email = "target@example.com")
                val gId = groupId("Viewer")
                val client = jsonClient()

                val body = client.post("/api/users/$targetId/assignments") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"group_id":"$gId","scope_type":"GLOBAL"}""")
                }
                    .body<JsonObject>()

                body["id"] shouldNotBe null
                body["scope_type"]!!.jsonPrimitive.content shouldBe "GLOBAL"
                body["group_id"]!!.jsonPrimitive.content shouldBe gId.toString()
            }
        }

        test("createAssignment returns 409 for duplicate assignment") {
            testApplication {
                testApp { _ -> configureAssignmentsTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val targetId = createUser(username = "target", email = "target@example.com")
                val gId = groupId("Viewer")
                assignGlobalGroup(targetId, "Viewer")

                val response = client.post("/api/users/$targetId/assignments") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"group_id":"$gId","scope_type":"GLOBAL"}""")
                }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("createAssignment returns 422 for NETWORK scope without scope_id") {
            testApplication {
                testApp { _ -> configureAssignmentsTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val targetId = createUser(username = "target", email = "target@example.com")
                val gId = groupId("Viewer")

                val response = client.post("/api/users/$targetId/assignments") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"group_id":"$gId","scope_type":"NETWORK"}""")
                }
                response.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }

        // ── DELETE /api/users/{userId}/assignments/{assignmentId} ─────────────────

        test("deleteAssignment returns 403 without permission") {
            testApplication {
                testApp { _ -> configureAssignmentsTest() }
                val userId = createUser()
                val targetId = createUser(username = "target", email = "target@example.com")
                val assignmentId = assignGlobalGroup(targetId, "Viewer")

                client.delete("/api/users/$targetId/assignments/$assignmentId") { bearerAuth(tokenFor(userId)) }
                    .status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("deleteAssignment removes assignment and returns 204") {
            testApplication {
                testApp { _ -> configureAssignmentsTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val targetId = createUser(username = "target", email = "target@example.com")
                val assignmentId = assignGlobalGroup(targetId, "Viewer")

                client.delete("/api/users/$targetId/assignments/$assignmentId") { bearerAuth(tokenFor(userId)) }
                    .status shouldBe HttpStatusCode.NoContent

                val exists = transaction {
                    UserGroupAssignments.selectAll()
                        .where { UserGroupAssignments.id eq assignmentId }
                        .firstOrNull() != null
                }
                exists shouldBe false
            }
        }

        test("deleteAssignment returns 404 for unknown assignment") {
            testApplication {
                testApp { _ -> configureAssignmentsTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val targetId = createUser(username = "target", email = "target@example.com")

                client.delete("/api/users/$targetId/assignments/${Uuid.random()}") { bearerAuth(tokenFor(userId)) }
                    .status shouldBe HttpStatusCode.NotFound
            }
        }
    })
