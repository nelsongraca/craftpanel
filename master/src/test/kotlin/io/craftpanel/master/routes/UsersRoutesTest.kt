package io.craftpanel.master.routes

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.auth.Argon2Hasher
import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.auth.TokenClaims
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.database.schema.Groups
import io.craftpanel.master.database.schema.UserGroupAssignments
import io.craftpanel.master.database.schema.Users
import io.craftpanel.master.service.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid
import io.craftpanel.master.service.ForbiddenException as ServiceForbiddenException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class UsersRoutesTest : FunSpec({
    val jwtConfig = JwtConfig(
        secret = "test-secret-that-is-at-least-32-characters!!",
        issuer = "craftpanel-test",
        audience = "craftpanel-test",
        expirySeconds = 900,
    )
    val jwtManager = JwtManager(jwtConfig)

    beforeTest {
        TestDatabase.initIfNeeded()
        TestDatabase.reset()
    }

    fun Application.configureTest() {
        install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(StatusPages) {
            exception<NotFoundException> { call, ex -> call.respond(HttpStatusCode.NotFound, mapOf("error" to (ex.message ?: "Not found"))) }
            exception<ServiceForbiddenException> { call, ex -> call.respond(HttpStatusCode.Forbidden, mapOf("error" to (ex.message ?: "Forbidden"))) }
            exception<ConflictException> { call, ex -> call.respond(HttpStatusCode.Conflict, mapOf("error" to (ex.message ?: "Conflict"))) }
            exception<UnprocessableException> { call, ex -> call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to (ex.message ?: "Unprocessable"))) }
            exception<BadGatewayException> { call, ex -> call.respond(HttpStatusCode.BadGateway, mapOf("error" to (ex.message ?: "Bad gateway"))) }
            exception<BadRequestException> { call, ex -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to (ex.message ?: "Bad request"))) }
        }
        install(Authentication) {
            jwt("auth-jwt") {
                realm = "CraftPanel"
                verifier(jwtManager.verifier)
                validate { credential ->
                    if (credential.payload.subject != null) JWTPrincipal(credential.payload) else null
                }
                challenge { _, _ ->
                    call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "Token is not valid or has expired"))
                }
            }
        }
        routing { usersRoutes(UserService()) }
    }

    fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    fun createUser(
        username: String = "admin",
        email: String = "admin@example.com",
        password: String = "hunter2",
        isActive: Boolean = true,
    ): Uuid = transaction {
        Users.insert {
            it[Users.username] = username
            it[Users.email] = email
            it[Users.passwordHash] = Argon2Hasher.hash(password)
            it[Users.isActive] = isActive
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

    fun tokenFor(userId: Uuid, username: String = "admin"): String =
        jwtManager.generate(TokenClaims(userId = userId, name = username, email = "$username@example.com", groups = emptyList()))

    // ── GET /api/users ────────────────────────────────────────────────────────

    test("listUsers returns 403 without system_users permission") {
        testApplication {
            application { configureTest() }
            val userId = createUser()
            val response = client.get("/api/users") { bearerAuth(tokenFor(userId)) }
            response.status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("listUsers returns wrapped list for super admin") {
        testApplication {
            application { configureTest() }
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
            application { configureTest() }
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
            application { configureTest() }
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
            application { configureTest() }
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

    // ── GET /api/users/{id} ───────────────────────────────────────────────────

    test("getUser returns 404 for unknown id") {
        testApplication {
            application { configureTest() }
            val userId = createUser()
            assignGlobalGroup(userId, "Super Admin")

            client.get("/api/users/${Uuid.random()}") { bearerAuth(tokenFor(userId)) }.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("getUser returns user with created_at") {
        testApplication {
            application { configureTest() }
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
            application { configureTest() }
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
            application { configureTest() }
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
            application { configureTest() }
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
            application { configureTest() }
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
            application { configureTest() }
            val userId = createUser()

            client.delete("/api/users/$userId") { bearerAuth(tokenFor(userId)) }.status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("deleteUser removes user and returns 204") {
        testApplication {
            application { configureTest() }
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
            application { configureTest() }
            val userId = createUser()
            assignGlobalGroup(userId, "Super Admin")

            client.delete("/api/users/${Uuid.random()}") { bearerAuth(tokenFor(userId)) }.status shouldBe HttpStatusCode.NotFound
        }
    }
})
