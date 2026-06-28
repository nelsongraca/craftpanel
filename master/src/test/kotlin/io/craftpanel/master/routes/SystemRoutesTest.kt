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
import io.craftpanel.master.service.repo.SettingsRepositoryImpl
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid
import io.craftpanel.master.service.ForbiddenException as ServiceForbiddenException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class SystemRoutesTest : FunSpec({
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
        routing { systemRoutes(SystemService(settingsRepository = SettingsRepositoryImpl())) }
    }

    fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
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

    fun tokenFor(userId: Uuid): String =
        jwtManager.generate(TokenClaims(userId = userId, name = "admin", email = "admin@example.com", groups = emptyList()))

    // ── GET /api/system/settings ──────────────────────────────────────────────

    test("getSystemSettings returns 403 for non-super-admin") {
        testApplication {
            application { configureTest() }
            val userId = createUser()
            assignGlobalGroup(userId, "Server Admin")

            client.get("/api/system/settings") { bearerAuth(tokenFor(userId)) }.status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("getSystemSettings returns defaults when no settings stored") {
        testApplication {
            application { configureTest() }
            val userId = createUser()
            assignGlobalGroup(userId, "Super Admin")
            val client = jsonClient()

            val body = client.get("/api/system/settings") { bearerAuth(tokenFor(userId)) }
                .body<JsonObject>()
            val settings = body["settings"]!!.jsonObject
            settings["metric_retention_days"]!!.jsonPrimitive.content shouldBe "30"
            settings["default_backup_max_count"]!!.jsonPrimitive.content shouldBe "10"
            (body["updated_at"] == null || body["updated_at"].toString() == "null") shouldBe true
        }
    }

    // ── PATCH /api/system/settings ────────────────────────────────────────────

    test("updateSystemSettings returns 403 for non-super-admin") {
        testApplication {
            application { configureTest() }
            val userId = createUser()
            assignGlobalGroup(userId, "Server Admin")

            val response = client.patch("/api/system/settings") {
                bearerAuth(tokenFor(userId))
                contentType(ContentType.Application.Json)
                setBody("""{"metric_retention_days":60}""")
            }
            response.status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("updateSystemSettings persists values and returns updated_by") {
        testApplication {
            application { configureTest() }
            val userId = createUser()
            assignGlobalGroup(userId, "Super Admin")
            val client = jsonClient()

            val body = client.patch("/api/system/settings") {
                bearerAuth(tokenFor(userId))
                contentType(ContentType.Application.Json)
                setBody("""{"metric_retention_days":60,"default_backup_max_count":14}""")
            }
                .body<JsonObject>()

            val settings = body["settings"]!!.jsonObject
            settings["metric_retention_days"]!!.jsonPrimitive.content shouldBe "60"
            settings["default_backup_max_count"]!!.jsonPrimitive.content shouldBe "14"
            body["updated_at"] shouldNotBe null
            body["updated_by"]!!.jsonPrimitive.content shouldBe userId.toString()
        }
    }

    test("updateSystemSettings returns 422 for invalid port range") {
        testApplication {
            application { configureTest() }
            val userId = createUser()
            assignGlobalGroup(userId, "Super Admin")

            val response = client.patch("/api/system/settings") {
                bearerAuth(tokenFor(userId))
                contentType(ContentType.Application.Json)
                setBody("""{"default_port_range_start":26000,"default_port_range_end":25000}""")
            }
            response.status shouldBe HttpStatusCode.UnprocessableEntity
        }
    }

    test("updateSystemSettings returns 422 for metric_retention_days less than 1") {
        testApplication {
            application { configureTest() }
            val userId = createUser()
            assignGlobalGroup(userId, "Super Admin")

            val response = client.patch("/api/system/settings") {
                bearerAuth(tokenFor(userId))
                contentType(ContentType.Application.Json)
                setBody("""{"metric_retention_days":0}""")
            }
            response.status shouldBe HttpStatusCode.UnprocessableEntity
        }
    }
})
