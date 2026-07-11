package io.craftpanel.master.auth.routes

import io.craftpanel.master.auth.*
import io.craftpanel.master.config.RateLimitConfig
import io.craftpanel.master.routes.ErrorResponse
import io.craftpanel.master.routes.userId
import io.craftpanel.master.service.repo.UserRepository
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class LoginResponse(@SerialName("access_token") val accessToken: String, @SerialName("expires_in") val expiresIn: Long)

@Serializable
data class WsTicketResponse(val ticket: String, @SerialName("expires_in") val expiresIn: Int)

@Serializable
data class MeResponse(val id: String, val username: String, val email: String, val groups: List<String>, val permissions: List<String>)

private data class UserRecord(val userId: Uuid, val username: String, val email: String, val passwordHash: String, val isActive: Boolean, val groupNames: List<String>)

private fun lookupUser(userRepository: UserRepository, email: String): UserRecord? {
    val credentials = userRepository.findCredentials(email) ?: return null
    val groups = userRepository.getUserGlobalGroups(credentials.userId)
        .map { it.groupName }

    return UserRecord(
        userId = credentials.userId,
        username = credentials.username,
        email = credentials.email,
        passwordHash = credentials.passwordHash,
        isActive = credentials.isActive,
        groupNames = groups
    )
}

private fun lookupUserById(userRepository: UserRepository, userId: Uuid): Triple<String, String, List<String>>? {
    val user = userRepository.findById(userId)
        ?.takeIf { it.isActive }
        ?: return null

    val groups = userRepository.getUserGlobalGroups(userId)
        .map { it.groupName }

    return Triple(user.username, user.email, groups)
}

fun Route.authRoutes(
    jwtManager: JwtManager,
    refreshTokenService: RefreshTokenService,
    wsTicketService: WsTicketService,
    userRepository: UserRepository,
    @Suppress("UNUSED_PARAMETER") rateLimitConfig: RateLimitConfig = RateLimitConfig(10, 30),
    secureCookies: Boolean = true
) {
    route("/api/auth") {
        rateLimit(RateLimitName("auth-login")) {
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
                val record = lookupUser(userRepository, req.email)
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
                    path = "/api/auth"
                )
                call.respond(LoginResponse(accessToken, jwtManager.expirySeconds))
            }
        } // rateLimit auth-login

        rateLimit(RateLimitName("auth-refresh")) {
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

                val (name, email, groupNames) = lookupUserById(userRepository, userId)
                    ?: run {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("User not found or inactive"))
                        return@post
                    }

                val accessToken = jwtManager.generate(
                    TokenClaims(userId = userId, name = name, email = email, groups = groupNames)
                )

                call.response.cookies.append(
                    name = "refresh_token",
                    value = newToken.rawToken,
                    httpOnly = true,
                    secure = secureCookies,
                    extensions = mapOf("SameSite" to "Strict"),
                    path = "/api/auth"
                )
                call.respond(LoginResponse(accessToken, jwtManager.expirySeconds))
            }
        } // rateLimit auth-refresh

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
                    name = "refresh_token",
                    value = "",
                    httpOnly = true,
                    secure = secureCookies,
                    extensions = mapOf("SameSite" to "Strict"),
                    path = "/api/auth",
                    maxAge = 0
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
                val userId = call.userId()
                refreshTokenService.revokeAll(userId)
                call.response.cookies.append(
                    name = "refresh_token",
                    value = "",
                    httpOnly = true,
                    secure = secureCookies,
                    extensions = mapOf("SameSite" to "Strict"),
                    path = "/api/auth",
                    maxAge = 0
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
                val userId = call.userId()
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
                val userId = call.userId()

                val (username, email, groupNames) = lookupUserById(userRepository, userId)
                    ?: run {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("User not found or inactive"))
                        return@get
                    }

                val permissions = PermissionResolver.resolve(userId)
                    .toList()
                    .sorted()

                call.respond(
                    MeResponse(
                        id = userId.toString(),
                        username = username,
                        email = email,
                        groups = groupNames,
                        permissions = permissions
                    )
                )
            }
        }
    }
}
