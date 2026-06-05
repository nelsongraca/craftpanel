package io.craftpanel.master.auth.routes

import io.craftpanel.master.auth.ScopeType
import io.craftpanel.master.auth.JWT_AUTH
import io.craftpanel.master.auth.*
import io.craftpanel.master.config.RateLimitConfig
import io.craftpanel.master.database.schema.Groups
import io.craftpanel.master.database.schema.UserGroupAssignments
import io.craftpanel.master.database.schema.Users
import io.craftpanel.master.routes.ErrorResponse
import io.craftpanel.master.util.toJavaUuid
import io.craftpanel.master.util.toKotlinUuid
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.github.tabilzad.ktor.annotations.KtorDescription
import io.github.tabilzad.ktor.annotations.KtorResponds
import io.github.tabilzad.ktor.annotations.ResponseEntry
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

fun Route.authRoutes(
    jwtManager: JwtManager,
    refreshTokenService: RefreshTokenService,
    wsTicketService: WsTicketService,
    @Suppress("UNUSED_PARAMETER") rateLimitConfig: RateLimitConfig = RateLimitConfig(10, 30),
    secureCookies: Boolean = true,
) {
    route("/api/auth") {
        rateLimit(RateLimitName("auth-login")) {
            @KtorResponds(mapping = [ResponseEntry("200", LoginResponse::class)])
            @KtorDescription(operationId = "authLogin", summary = "Login")
            post("/login") {
                val req = call.receive<LoginRequest>()
                val record = lookupUser(req.email)
                val hashToVerify = record?.passwordHash ?: Argon2Hasher.DUMMY_HASH
                val passwordOk = Argon2Hasher.verify(req.password, hashToVerify)

                if (record == null || !record.isActive || !passwordOk) {
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
                    secure = secureCookies,
                    extensions = mapOf("SameSite" to "Strict"),
                    path = "/api/auth",
                )
                call.respond(LoginResponse(accessToken, jwtManager.expirySeconds))
            }
        } // rateLimit auth-login

        rateLimit(RateLimitName("auth-refresh")) {
            @KtorResponds(mapping = [ResponseEntry("200", LoginResponse::class)])
            @KtorDescription(operationId = "authRefresh", summary = "Refresh access token")
            post("/refresh") {
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
                    secure = secureCookies,
                    extensions = mapOf("SameSite" to "Strict"),
                    path = "/api/auth",
                )
                call.respond(LoginResponse(accessToken, jwtManager.expirySeconds))
            }
        } // rateLimit auth-refresh

        authenticate(JWT_AUTH) {
            @KtorDescription(operationId = "authLogout", summary = "Logout")
            post("/logout") {
                val rawToken = call.request.cookies["refresh_token"]
                if (rawToken != null) refreshTokenService.revoke(rawToken)
                call.response.cookies.append(
                    name = "refresh_token", value = "", httpOnly = true, secure = secureCookies,
                    extensions = mapOf("SameSite" to "Strict"), path = "/api/auth", maxAge = 0,
                )
                call.respond(HttpStatusCode.NoContent)
            }

            @KtorDescription(operationId = "authLogoutAll", summary = "Logout all sessions")
            post("/logout-all") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                refreshTokenService.revokeAll(userId)
                call.response.cookies.append(
                    name = "refresh_token", value = "", httpOnly = true, secure = secureCookies,
                    extensions = mapOf("SameSite" to "Strict"), path = "/api/auth", maxAge = 0,
                )
                call.respond(HttpStatusCode.NoContent)
            }

            @KtorResponds(mapping = [ResponseEntry("200", WsTicketResponse::class)])
            @KtorDescription(operationId = "authWsTicket", summary = "Issue WebSocket upgrade ticket")
            post("/ws-ticket") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                val (ticket, expiresIn) = wsTicketService.issue(userId)
                call.respond(WsTicketResponse(ticket, expiresIn))
            }

            @KtorResponds(mapping = [ResponseEntry("200", MeResponse::class)])
            @KtorDescription(operationId = "authMe", summary = "Get current user")
            get("/me") {
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
