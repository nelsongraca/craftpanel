package io.craftpanel.master.routes

import io.craftpanel.master.*
import io.craftpanel.master.auth.*
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.database.schema.*
import io.craftpanel.master.service.UserService
import io.craftpanel.master.service.repo.impl.UserRepositoryImpl
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

class UsersRoutesTest :
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

        fun Route.configureUsersTest() {
            usersRoutes(UserService(UserRepositoryImpl()))
        }

        fun createUser(username: String = "admin", email: String = "admin@example.com", password: String = "hunter2", isActive: Boolean = true, mustChangePassword: Boolean = false): Uuid =
            transaction {
                Users.insert {
                    it[Users.username] = username
                    it[Users.email] = email
                    it[Users.passwordHash] = Argon2Hasher.hash(password)
                    it[Users.isActive] = isActive
                    it[Users.mustChangePassword] = mustChangePassword
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

        // ── GET /api/users ────────────────────────────────────────────────────────

        test("listUsers returns 403 without system_users permission") {
            testApplication {
                testApp { _ -> configureUsersTest() }
                val userId = createUser()
                val response = client.get("/api/users") { bearerAuth(tokenFor(userId)) }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("listUsers returns wrapped list for super admin") {
            testApplication {
                testApp { _ -> configureUsersTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                createUser(username = "other", email = "other@example.com")
                val client = jsonClient()

                val body = client.get("/api/users") { bearerAuth(tokenFor(userId)) }
                    .body<JsonObject>()
                val users = body["users"]!!.jsonArray
                users.size shouldBe 2
                users[0].jsonObject["created_at"] shouldNotBe null
                users[0].jsonObject["is_active"] shouldNotBe null
            }
        }

        // ── POST /api/users ───────────────────────────────────────────────────────

        test("createUser returns 403 without permission") {
            testApplication {
                testApp { _ -> configureUsersTest() }
                val userId = createUser()
                val response = client.post("/api/users") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"new","email":"new@x.com","password":"p"}""")
                }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("createUser returns full user object on 201") {
            testApplication {
                testApp { _ -> configureUsersTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val client = jsonClient()

                val body = client.post("/api/users") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"newuser","email":"newuser@example.com","password":"secret"}""")
                }
                    .body<JsonObject>()

                body["username"]!!.jsonPrimitive.content shouldBe "newuser"
                body["id"] shouldNotBe null
                body["created_at"] shouldNotBe null
                body["is_active"]!!.jsonPrimitive.content.toBoolean() shouldBe true
            }
        }

        test("createUser returns 409 for duplicate username") {
            testApplication {
                testApp { _ -> configureUsersTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")

                val response = client.post("/api/users") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"admin","email":"different@example.com","password":"p"}""")
                }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("createUser with force_password_change sets must_change_password") {
            testApplication {
                testApp { _ -> configureUsersTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")

                val body = jsonClient().post("/api/users") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"newuser","email":"newuser@example.com","password":"secret","force_password_change":true}""")
                }
                    .body<JsonObject>()

                body["must_change_password"]!!.jsonPrimitive.content.toBoolean() shouldBe true
            }
        }

        test("createUser without force_password_change leaves must_change_password false") {
            testApplication {
                testApp { _ -> configureUsersTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")

                jsonClient().post("/api/users") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"newuser","email":"newuser@example.com","password":"secret"}""")
                }.status shouldBe HttpStatusCode.Created

                val mustChange = transaction {
                    Users.selectAll()
                        .where { Users.username eq "newuser" }
                        .first()[Users.mustChangePassword]
                }
                mustChange shouldBe false
            }
        }

        // ── GET /api/users/{id} ───────────────────────────────────────────────────

        test("getUser returns 404 for unknown id") {
            testApplication {
                testApp { _ -> configureUsersTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")

                client.get("/api/users/${Uuid.random()}") { bearerAuth(tokenFor(userId)) }.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("getUser returns user with created_at") {
            testApplication {
                testApp { _ -> configureUsersTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val client = jsonClient()

                val body = client.get("/api/users/$userId") { bearerAuth(tokenFor(userId)) }
                    .body<JsonObject>()
                body["id"]!!.jsonPrimitive.content shouldBe userId.toString()
                body["created_at"] shouldNotBe null
            }
        }

        // ── PATCH /api/users/{id} ─────────────────────────────────────────────────

        test("updateUser returns 403 without permission") {
            testApplication {
                testApp { _ -> configureUsersTest() }
                val userId = createUser()
                val response = client.patch("/api/users/$userId") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"new"}""")
                }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("updateUser updates email and is_active") {
            testApplication {
                testApp { _ -> configureUsersTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val targetId = createUser(username = "target", email = "target@example.com")
                val client = jsonClient()

                val body = client.patch("/api/users/$targetId") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"email":"updated@example.com","is_active":false}""")
                }
                    .body<JsonObject>()

                body["email"]!!.jsonPrimitive.content shouldBe "updated@example.com"
                body["is_active"]!!.jsonPrimitive.content.toBoolean() shouldBe false
            }
        }

        test("updateUser returns 422 on duplicate email") {
            testApplication {
                testApp { _ -> configureUsersTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                createUser(username = "other", email = "other@example.com")

                val response = client.patch("/api/users/$userId") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"email":"other@example.com"}""")
                }
                response.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }

        test("updateUser returns 404 for unknown user") {
            testApplication {
                testApp { _ -> configureUsersTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")

                val response = client.patch("/api/users/${Uuid.random()}") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"x"}""")
                }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        // ── DELETE /api/users/{id} ────────────────────────────────────────────────

        test("deleteUser returns 403 without permission") {
            testApplication {
                testApp { _ -> configureUsersTest() }
                val userId = createUser()

                client.delete("/api/users/$userId") { bearerAuth(tokenFor(userId)) }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("deleteUser removes user and returns 204") {
            testApplication {
                testApp { _ -> configureUsersTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val targetId = createUser(username = "target", email = "target@example.com")

                client.delete("/api/users/$targetId") { bearerAuth(tokenFor(userId)) }.status shouldBe HttpStatusCode.NoContent

                val exists = transaction {
                    Users.selectAll()
                        .where { Users.id eq targetId }
                        .firstOrNull() != null
                }
                exists shouldBe false
            }
        }

        test("deleteUser returns 404 for unknown user") {
            testApplication {
                testApp { _ -> configureUsersTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")

                client.delete("/api/users/${Uuid.random()}") { bearerAuth(tokenFor(userId)) }.status shouldBe HttpStatusCode.NotFound
            }
        }

        // ── PUT /api/users/{id}/password ─────────────────────────────────────────

        test("resetUserPassword returns 403 without permission") {
            testApplication {
                testApp { _ -> configureUsersTest() }
                val userId = createUser()
                val targetId = createUser(username = "target", email = "target@example.com")

                val response = client.put("/api/users/$targetId/password") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"password":"newpass123"}""")
                }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("resetUserPassword updates stored hash and returns 204") {
            testApplication {
                testApp { _ -> configureUsersTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val targetId = createUser(username = "target", email = "target@example.com", password = "oldpass")

                val response = client.put("/api/users/$targetId/password") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"password":"newpass123"}""")
                }
                response.status shouldBe HttpStatusCode.NoContent

                // Old password should no longer verify; new one should.
                val hash = transaction {
                    Users.selectAll()
                        .where { Users.id eq targetId }
                        .first()[Users.passwordHash]
                }
                Argon2Hasher.verify("oldpass", hash) shouldBe false
                Argon2Hasher.verify("newpass123", hash) shouldBe true
            }
        }

        test("resetUserPassword returns 404 for unknown user") {
            testApplication {
                testApp { _ -> configureUsersTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")

                val response = client.put("/api/users/${Uuid.random()}/password") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"password":"newpass123"}""")
                }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("resetUserPassword cannot target the caller's own account") {
            testApplication {
                testApp { _ -> configureUsersTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")

                val response = client.put("/api/users/$userId/password") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"password":"newpass123"}""")
                }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("resetUserPassword sets must_change_password by default") {
            testApplication {
                testApp { _ -> configureUsersTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val targetId = createUser(username = "target", email = "target@example.com")

                client.put("/api/users/$targetId/password") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"password":"newpass123"}""")
                }.status shouldBe HttpStatusCode.NoContent

                val mustChange = transaction {
                    Users.selectAll()
                        .where { Users.id eq targetId }
                        .first()[Users.mustChangePassword]
                }
                mustChange shouldBe true
            }
        }

        test("resetUserPassword with force_password_change false leaves must_change_password unset") {
            testApplication {
                testApp { _ -> configureUsersTest() }
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val targetId = createUser(username = "target", email = "target@example.com")

                val response = client.put("/api/users/$targetId/password") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody("""{"password":"newpass123","force_password_change":false}""")
                }
                response.status shouldBe HttpStatusCode.NoContent

                val mustChange = transaction {
                    Users.selectAll()
                        .where { Users.id eq targetId }
                        .first()[Users.mustChangePassword]
                }
                mustChange shouldBe false
            }
        }
    })
