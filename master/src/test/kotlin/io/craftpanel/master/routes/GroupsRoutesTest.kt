package io.craftpanel.master.routes

import io.craftpanel.master.*
import io.craftpanel.master.auth.*
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.database.schema.*
import io.craftpanel.master.service.GroupService
import io.craftpanel.master.service.repo.impl.GroupRepositoryImpl
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

class GroupsRoutesTest :
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

        fun Route.configureGroupsTest() {
            groupsRoutes(GroupService(GroupRepositoryImpl()))
        }

        fun createUser(username: String = "admin", email: String = "admin@example.com"): Uuid = transaction {
            Users.insert {
                it[Users.username] = username
                it[Users.email] = email
                it[Users.passwordHash] = Argon2Hasher.hash("pass")
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

        fun createGroup(name: String): Uuid = transaction {
            Groups.insert { it[Groups.name] = name }[Groups.id].let { Uuid.parse(it.toString()) }
        }

        fun tokenFor(userId: Uuid): String = jwtManager.generate(TokenClaims(userId = userId, name = "admin", email = "admin@example.com", groups = emptyList()))

        // ── GET /api/groups ───────────────────────────────────────────────────────

        test("listGroups returns 403 without system_users permission") {
            testApplication {
                testApp { _ -> configureGroupsTest() }
                val userId = createUser()
                client.get("/api/groups") { bearerAuth(tokenFor(userId)) }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("listGroups returns groups with is_system and created_at") {
            testApplication {
                testApp { _ -> configureGroupsTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val client = jsonClient()

                val body = client.get("/api/groups") { bearerAuth(tokenFor(userId)) }
                    .body<List<JsonObject>>()
                val superAdmin = body.first { it["name"]!!.jsonPrimitive.content == "Super Admin" }
                superAdmin["is_system"]!!.jsonPrimitive.content.toBoolean() shouldBe true
                superAdmin["created_at"] shouldNotBe null
            }
        }

        // ── POST /api/groups ──────────────────────────────────────────────────────

        test("createGroup returns 403 without permission") {
            testApplication {
                testApp { _ -> configureGroupsTest() }
                val userId = createUser()
                val response = client.post("/api/groups") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"Mods"}""")
                }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("createGroup returns full group object on 201") {
            testApplication {
                testApp { _ -> configureGroupsTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val client = jsonClient()

                val body = client.post("/api/groups") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"Moderators"}""")
                }
                    .body<JsonObject>()

                body["name"]!!.jsonPrimitive.content shouldBe "Moderators"
                body["is_system"]!!.jsonPrimitive.content.toBoolean() shouldBe false
                body["id"] shouldNotBe null
                body["created_at"] shouldNotBe null
            }
        }

        test("createGroup returns 409 for duplicate name") {
            testApplication {
                testApp { _ -> configureGroupsTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                createGroup("Duped")

                val response = client.post("/api/groups") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"Duped"}""")
                }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        // ── PATCH /api/groups/{id} ────────────────────────────────────────────────

        test("updateGroup returns 409 for system group") {
            testApplication {
                testApp { _ -> configureGroupsTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val superAdminId = transaction {
                    Groups.selectAll()
                        .where { Groups.name eq "Super Admin" }
                        .first()[Groups.id].let { Uuid.parse(it.toString()) }
                }

                val response = client.patch("/api/groups/$superAdminId") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"Hacked"}""")
                }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("updateGroup renames non-system group") {
            testApplication {
                testApp { _ -> configureGroupsTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val groupId = createGroup("OldName")
                val client = jsonClient()

                val body = client.patch("/api/groups/$groupId") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"NewName"}""")
                }
                    .body<JsonObject>()

                body["name"]!!.jsonPrimitive.content shouldBe "NewName"
            }
        }

        test("updateGroup returns 404 for unknown group") {
            testApplication {
                testApp { _ -> configureGroupsTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")

                val response = client.patch("/api/groups/${Uuid.random()}") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"x"}""")
                }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        // ── DELETE /api/groups/{id} ───────────────────────────────────────────────

        test("deleteGroup returns 409 for system group") {
            testApplication {
                testApp { _ -> configureGroupsTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val superAdminId = transaction {
                    Groups.selectAll()
                        .where { Groups.name eq "Super Admin" }
                        .first()[Groups.id].let { Uuid.parse(it.toString()) }
                }

                client.delete("/api/groups/$superAdminId") { bearerAuth(tokenFor(userId)) }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("deleteGroup removes non-system group") {
            testApplication {
                testApp { _ -> configureGroupsTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val groupId = createGroup("Deletable")

                client.delete("/api/groups/$groupId") { bearerAuth(tokenFor(userId)) }.status shouldBe HttpStatusCode.NoContent

                val exists = transaction {
                    Groups.selectAll()
                        .where { Groups.id eq groupId }
                        .firstOrNull() != null
                }
                exists shouldBe false
            }
        }

        test("deleteGroup returns 404 for unknown group") {
            testApplication {
                testApp { _ -> configureGroupsTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")

                client.delete("/api/groups/${Uuid.random()}") { bearerAuth(tokenFor(userId)) }.status shouldBe HttpStatusCode.NotFound
            }
        }

        // ── PUT /api/groups/{id}/permissions ──────────────────────────────────────

        test("setGroupPermissions replaces permission set") {
            testApplication {
                testApp { _ -> configureGroupsTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val groupId = createGroup("Custom")
                val client = jsonClient()

                val body = client.put("/api/groups/$groupId/permissions") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"permissions":["server.view","server.console"]}""")
                }
                    .body<JsonObject>()

                val perms = body["permissions"]!!.jsonArray.map { it.jsonPrimitive.content }
                perms.toSet() shouldBe setOf("server.view", "server.console")
            }
        }

        test("setGroupPermissions returns 400 for invalid permission node") {
            testApplication {
                testApp { _ -> configureGroupsTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val groupId = createGroup("Custom")

                val response = client.put("/api/groups/$groupId/permissions") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"permissions":["server.view","not.a.real.perm"]}""")
                }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("setGroupPermissions returns 409 for system group") {
            testApplication {
                testApp { _ -> configureGroupsTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val viewerId = transaction {
                    Groups.selectAll()
                        .where { Groups.name eq "Viewer" }
                        .first()[Groups.id].let { Uuid.parse(it.toString()) }
                }

                val response = client.put("/api/groups/$viewerId/permissions") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"permissions":["server.view"]}""")
                }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }
    })
