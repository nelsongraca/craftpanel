package io.craftpanel.master.auth.routes

import io.craftpanel.master.auth.Argon2Hasher
import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.auth.RefreshTokenService
import io.craftpanel.master.auth.TokenClaims
import io.craftpanel.master.database.schema.Groups
import io.craftpanel.master.database.schema.UserGroupAssignments
import io.craftpanel.master.database.schema.Users
import io.craftpanel.master.util.toJavaUuid
import io.craftpanel.master.util.toKotlinUuid
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class LoginResponse(val accessToken: String)

private data class UserRecord(
    val userId: UUID,
    val username: String,
    val email: String,
    val passwordHash: String,
    val isActive: Boolean,
    val groupNames: List<String>,
)

private fun lookupUser(email: String): UserRecord? = transaction {
    val user = Users.selectAll()
        .where { Users.email eq email }
        .firstOrNull() ?: return@transaction null

    val kotlinId = user[Users.id]

    val groups = (UserGroupAssignments innerJoin Groups)
        .selectAll()
        .where {
            (UserGroupAssignments.userId eq kotlinId) and
            (UserGroupAssignments.scopeType eq "GLOBAL")
        }
        .map { it[Groups.name] }

    UserRecord(
        userId = kotlinId.toJavaUuid(),
        username = user[Users.username],
        email = user[Users.email],
        passwordHash = user[Users.passwordHash],
        isActive = user[Users.isActive],
        groupNames = groups,
    )
}

private fun lookupUserById(userId: UUID): Triple<String, String, List<String>>? = transaction {
    val kotlinId = userId.toKotlinUuid()
    val user = Users.selectAll()
        .where { Users.id eq kotlinId }
        .firstOrNull() ?: return@transaction null

    val groups = (UserGroupAssignments innerJoin Groups)
        .selectAll()
        .where {
            (UserGroupAssignments.userId eq kotlinId) and
            (UserGroupAssignments.scopeType eq "GLOBAL")
        }
        .map { it[Groups.name] }

    Triple(user[Users.username], user[Users.email], groups)
}

fun Route.authRoutes(jwtManager: JwtManager, refreshTokenService: RefreshTokenService) {
    route("/api/v1/auth") {
        post("/login") {
            val req = call.receive<LoginRequest>()
            val record = lookupUser(req.email)

            if (record == null || !record.isActive || !Argon2Hasher.verify(req.password, record.passwordHash)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid credentials"))
                return@post
            }

            val accessToken = jwtManager.generate(
                TokenClaims(userId = record.userId, name = record.username, email = record.email, groups = record.groupNames)
            )
            val refreshResult = refreshTokenService.issue(record.userId)

            call.response.cookies.append(
                name = "refresh_token",
                value = refreshResult.rawToken,
                httpOnly = true,
                secure = true,
                extensions = mapOf("SameSite" to "Strict"),
                path = "/api/v1/auth",
            )
            call.respond(LoginResponse(accessToken))
        }

        post("/refresh") {
            val rawToken = call.request.cookies["refresh_token"]
                ?: run {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "No refresh token"))
                    return@post
                }

            val (userId, newToken) = refreshTokenService.rotate(rawToken)
                ?: run {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or expired refresh token"))
                    return@post
                }

            val (name, email, groupNames) = lookupUserById(userId)
                ?: run { call.respond(HttpStatusCode.Unauthorized); return@post }

            val accessToken = jwtManager.generate(
                TokenClaims(userId = userId, name = name, email = email, groups = groupNames)
            )

            call.response.cookies.append(
                name = "refresh_token",
                value = newToken.rawToken,
                httpOnly = true,
                secure = true,
                extensions = mapOf("SameSite" to "Strict"),
                path = "/api/v1/auth",
            )
            call.respond(LoginResponse(accessToken))
        }

        authenticate("auth-jwt") {
            post("/logout") {
                val rawToken = call.request.cookies["refresh_token"]
                if (rawToken != null) refreshTokenService.rotate(rawToken)
                call.response.cookies.append(
                    name = "refresh_token", value = "", httpOnly = true, secure = true,
                    extensions = mapOf("SameSite" to "Strict"), path = "/api/v1/auth", maxAge = 0,
                )
                call.respond(HttpStatusCode.NoContent)
            }

            post("/logout-all") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                refreshTokenService.revokeAll(userId)
                call.response.cookies.append(
                    name = "refresh_token", value = "", httpOnly = true, secure = true,
                    extensions = mapOf("SameSite" to "Strict"), path = "/api/v1/auth", maxAge = 0,
                )
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
