package io.craftpanel.master.auth.routes

import io.craftpanel.master.auth.ScopeType
import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.JWT_AUTH
import io.craftpanel.master.auth.*
import io.craftpanel.master.database.schema.Groups
import io.craftpanel.master.database.schema.UserGroupAssignments
import io.craftpanel.master.database.schema.Users
import io.craftpanel.master.routes.ErrorResponse
import io.craftpanel.master.util.toJavaUuid
import io.craftpanel.master.util.toKotlinUuid
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.*

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class LoginResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long,
)

@Serializable
data class WsTicketResponse(
    val ticket: String,
    @SerialName("expires_in") val expiresIn: Int,
)

@Serializable
data class MeResponse(
    val id: String,
    val username: String,
    val email: String,
    val groups: List<String>,
    val permissions: List<String>,
)

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
                    (UserGroupAssignments.scopeType eq ScopeType.GLOBAL.name)
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
        .where { (Users.id eq kotlinId) and (Users.isActive eq true) }
        .firstOrNull() ?: return@transaction null

    val groups = (UserGroupAssignments innerJoin Groups)
        .selectAll()
        .where {
            (UserGroupAssignments.userId eq kotlinId) and
                    (UserGroupAssignments.scopeType eq ScopeType.GLOBAL.name)
        }
        .map { it[Groups.name] }

    Triple(user[Users.username], user[Users.email], groups)
}

fun Route.authRoutes(jwtManager: JwtManager, refreshTokenService: RefreshTokenService, wsTicketService: WsTicketService) {
    route("/api/auth") {
        post("/login", {
            operationId = "authLogin"
            summary = "Login"
            securitySchemeNames = emptyList()
            request { body<LoginRequest>() }
            response {
                code(HttpStatusCode.OK) { body<LoginResponse>() }
                code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
            }
        }) {
            val req = call.receive<LoginRequest>()
            val record = lookupUser(req.email)

            if (record == null || !record.isActive || !Argon2Hasher.verify(req.password, record.passwordHash)) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid credentials"))
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
                path = "/api/auth",
            )
            call.respond(LoginResponse(accessToken, jwtManager.expirySeconds))
        }

        post("/refresh", {
            operationId = "authRefresh"
            summary = "Refresh access token"
            securitySchemeNames = emptyList()
            response {
                code(HttpStatusCode.OK) { body<LoginResponse>() }
                code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
            }
        }) {
            val rawToken = call.request.cookies["refresh_token"]
                ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("No refresh token"))
                    return@post
                }

            val (userId, newToken) = refreshTokenService.rotate(rawToken)
                ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or expired refresh token"))
                    return@post
                }

            val (name, email, groupNames) = lookupUserById(userId)
                ?: run { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("User not found or inactive")); return@post }

            val accessToken = jwtManager.generate(
                TokenClaims(userId = userId, name = name, email = email, groups = groupNames)
            )

            call.response.cookies.append(
                name = "refresh_token",
                value = newToken.rawToken,
                httpOnly = true,
                secure = true,
                extensions = mapOf("SameSite" to "Strict"),
                path = "/api/auth",
            )
            call.respond(LoginResponse(accessToken, jwtManager.expirySeconds))
        }

        authenticate(JWT_AUTH) {
            post("/logout", {
                operationId = "authLogout"
                summary = "Logout"
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val rawToken = call.request.cookies["refresh_token"]
                if (rawToken != null) refreshTokenService.revoke(rawToken)
                call.response.cookies.append(
                    name = "refresh_token", value = "", httpOnly = true, secure = true,
                    extensions = mapOf("SameSite" to "Strict"), path = "/api/auth", maxAge = 0,
                )
                call.respond(HttpStatusCode.NoContent)
            }

            post("/logout-all", {
                operationId = "authLogoutAll"
                summary = "Logout all sessions"
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                refreshTokenService.revokeAll(userId)
                call.response.cookies.append(
                    name = "refresh_token", value = "", httpOnly = true, secure = true,
                    extensions = mapOf("SameSite" to "Strict"), path = "/api/auth", maxAge = 0,
                )
                call.respond(HttpStatusCode.NoContent)
            }

            post("/ws-ticket", {
                operationId = "authWsTicket"
                summary = "Issue WebSocket upgrade ticket"
                response {
                    code(HttpStatusCode.OK) { body<WsTicketResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                val (ticket, expiresIn) = wsTicketService.issue(userId)
                call.respond(WsTicketResponse(ticket, expiresIn))
            }

            get("/me", {
                operationId = "authMe"
                summary = "Get current user"
                response {
                    code(HttpStatusCode.OK) { body<MeResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)

                val (username, email, groupNames) = lookupUserById(userId)
                    ?: run { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("User not found or inactive")); return@get }

                val permissions = PermissionResolver.resolve(userId)
                    .toList()
                    .sorted()

                call.respond(
                    MeResponse(
                        id = userId.toString(),
                        username = username,
                        email = email,
                        groups = groupNames,
                        permissions = permissions,
                    )
                )
            }
        }
    }
}
