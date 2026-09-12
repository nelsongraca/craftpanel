package io.craftpanel.master.auth.routes

import com.auth0.jwt.exceptions.JWTVerificationException
import io.craftpanel.master.auth.*
import io.craftpanel.master.config.RateLimitConfig
import io.craftpanel.master.routes.ErrorResponse
import io.craftpanel.master.routes.userId
import io.craftpanel.master.service.repo.RecoveryCodeRepository
import io.craftpanel.master.service.repo.UserRepository
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    @SerialName("device_fingerprint") val deviceFingerprint: String? = null
)

@Serializable
data class LoginResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("requires_totp") val requiresTotp: Boolean = false,
    @SerialName("temp_token") val tempToken: String? = null,
    @SerialName("must_change_password") val mustChangePassword: Boolean = false
)

@Serializable
data class TotpVerifyRequest(
    @SerialName("temp_token") val tempToken: String,
    val code: String,
    @SerialName("trust_device") val trustDevice: Boolean = false,
    @SerialName("device_fingerprint") val deviceFingerprint: String? = null
)

@Serializable
data class TotpRecoveryRequest(@SerialName("temp_token") val tempToken: String, val code: String)

@Serializable
data class TotpSetupResponse(
    val secret: String,
    @SerialName("qr_data_uri") val qrDataUri: String,
    @SerialName("recovery_codes") val recoveryCodes: List<String>
)

@Serializable
data class TotpStatusResponse(
    val enabled: Boolean,
    @SerialName("recovery_codes_remaining") val recoveryCodesRemaining: Int
)

@Serializable
data class TotpEnableRequest(val code: String)

@Serializable
data class TotpDisableRequest(val code: String)

@Serializable
data class WsTicketResponse(val ticket: String, @SerialName("expires_in") val expiresIn: Int)

@Serializable
data class ChangePasswordRequest(
    @SerialName("old_password") val oldPassword: String,
    @SerialName("new_password") val newPassword: String
)

@Serializable
data class MeResponse(
    val id: String,
    val username: String,
    val email: String,
    val groups: List<String>,
    val permissions: List<String>,
    @SerialName("server_permissions") val serverPermissions: Map<String, List<String>>,
    @SerialName("totp_enabled") val totpEnabled: Boolean,
    @SerialName("must_change_password") val mustChangePassword: Boolean = false
)

private data class UserRecord(
    val userId: Uuid,
    val username: String,
    val email: String,
    val passwordHash: String,
    val isActive: Boolean,
    val totpEnabled: Boolean,
    val mustChangePassword: Boolean,
    val groupNames: List<String>
)

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
        totpEnabled = credentials.totpEnabled,
        mustChangePassword = credentials.mustChangePassword,
        groupNames = groups
    )
}

private data class UserBasicInfo(val username: String, val email: String, val mustChangePassword: Boolean, val groupNames: List<String>)

private fun lookupUserById(userRepository: UserRepository, userId: Uuid): UserBasicInfo? {
    val user = userRepository.findById(userId)
        ?.takeIf { it.isActive }
        ?: return null

    val groups = userRepository.getUserGlobalGroups(userId)
        .map { it.groupName }

    return UserBasicInfo(
        username = user.username,
        email = user.email,
        mustChangePassword = user.mustChangePassword,
        groupNames = groups
    )
}

private fun verifyTotpTempToken(jwtManager: JwtManager, rawToken: String): Uuid? {
    val decoded = try {
        jwtManager.verifier.verify(rawToken)
    }
    catch (_: JWTVerificationException) {
        return null
    }
    if (decoded.getClaim("totp_challenge")
            .asBoolean() != true
    ) return null
    return decoded.subject?.let { runCatching { Uuid.parse(it) }.getOrNull() }
}

private fun ApplicationCall.issueSessionCookies(
    jwtManager: JwtManager,
    refreshTokenService: RefreshTokenService,
    userId: Uuid,
    username: String,
    email: String,
    groupNames: List<String>,
    mustChangePassword: Boolean,
    secureCookies: Boolean,
    cookieDomainOrNull: String?,
    trustDevice: Boolean = false,
    deviceFingerprint: String? = null
): LoginResponse {
    val accessToken = jwtManager.generate(
        TokenClaims(userId = userId, name = username, email = email, groups = groupNames)
    )
    val refreshResult = refreshTokenService.issue(userId, trusted = trustDevice, deviceFingerprint = deviceFingerprint.takeIf { trustDevice })

    response.cookies.append(
        name = "refresh_token",
        value = refreshResult.rawToken,
        httpOnly = true,
        secure = secureCookies,
        extensions = mapOf("SameSite" to "Strict"),
        path = "/api/auth",
        domain = cookieDomainOrNull
    )
    return LoginResponse(
        accessToken = accessToken,
        expiresIn = jwtManager.expirySeconds,
        mustChangePassword = mustChangePassword
    )
}

fun Route.authRoutes(
    jwtManager: JwtManager,
    refreshTokenService: RefreshTokenService,
    wsTicketService: WsTicketService,
    userRepository: UserRepository,
    totpService: TotpService,
    recoveryCodeRepository: RecoveryCodeRepository,
    @Suppress("UNUSED_PARAMETER") rateLimitConfig: RateLimitConfig = RateLimitConfig(10, 30, 10),
    secureCookies: Boolean = true,
    cookieDomain: String = ""
) {
    val cookieDomainOrNull = cookieDomain.ifBlank { null }
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

                if (record.totpEnabled) {
                    val rawToken = call.request.cookies["refresh_token"]
                    val fingerprint = req.deviceFingerprint
                    val skipTotp = rawToken != null && fingerprint != null &&
                        refreshTokenService.isTrustedAndFingerprintValid(rawToken, fingerprint)

                    if (skipTotp) {
                        call.respond(
                            call.issueSessionCookies(
                                jwtManager = jwtManager,
                                refreshTokenService = refreshTokenService,
                                userId = record.userId,
                                username = record.username,
                                email = record.email,
                                groupNames = record.groupNames,
                                mustChangePassword = record.mustChangePassword,
                                secureCookies = secureCookies,
                                cookieDomainOrNull = cookieDomainOrNull
                            )
                        )
                        return@post
                    }

                    val tempToken = jwtManager.generateTotpTempToken(record.userId)
                    call.respond(
                        LoginResponse(
                            expiresIn = JwtManager.tempTokenTtlSeconds,
                            requiresTotp = true,
                            tempToken = tempToken,
                            mustChangePassword = record.mustChangePassword
                        )
                    )
                    return@post
                }

                call.respond(
                    call.issueSessionCookies(
                        jwtManager = jwtManager,
                        refreshTokenService = refreshTokenService,
                        userId = record.userId,
                        username = record.username,
                        email = record.email,
                        groupNames = record.groupNames,
                        mustChangePassword = record.mustChangePassword,
                        secureCookies = secureCookies,
                        cookieDomainOrNull = cookieDomainOrNull
                    )
                )
            }
        } // rateLimit auth-login

        rateLimit(RateLimitName("auth-totp-verify")) {
            post("/totp-verify", {
                operationId = "authTotpVerify"
                summary = "Verify TOTP code after password login"
                securitySchemeNames = emptyList()
                request { body<TotpVerifyRequest>() }
                response {
                    code(HttpStatusCode.OK) { body<LoginResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                    code(HttpStatusCode.BadRequest) { body<ErrorResponse>() }
                }
            }) {
                val req = call.receive<TotpVerifyRequest>()
                val userId = verifyTotpTempToken(jwtManager, req.tempToken)
                    ?: run {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or expired login"))
                        return@post
                    }

                val user = userRepository.findById(userId)
                val record = user?.let { userRepository.findCredentials(it.email) }
                if (record == null || !record.isActive) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("User not found or inactive"))
                    return@post
                }
                if (!record.totpEnabled) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("TOTP is not enabled"))
                    return@post
                }

                val storedSecret = userRepository.findTotpSecret(userId)
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("TOTP is not enabled"))
                        return@post
                    }

                val secret = runCatching { totpService.decryptSecret(storedSecret) }.getOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Could not read TOTP secret"))
                        return@post
                    }

                if (!totpService.validate(secret, req.code.trim())) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid verification code"))
                    return@post
                }

                val groups = userRepository.getUserGlobalGroups(userId)
                    .map { it.groupName }
                call.respond(
                    call.issueSessionCookies(
                        jwtManager = jwtManager,
                        refreshTokenService = refreshTokenService,
                        userId = userId,
                        username = record.username,
                        email = record.email,
                        groupNames = groups,
                        mustChangePassword = record.mustChangePassword,
                        secureCookies = secureCookies,
                        cookieDomainOrNull = cookieDomainOrNull,
                        trustDevice = req.trustDevice,
                        deviceFingerprint = req.deviceFingerprint
                    )
                )
            }

            post("/totp-recovery", {
                operationId = "authTotpRecovery"
                summary = "Verify recovery code after password login"
                securitySchemeNames = emptyList()
                request { body<TotpRecoveryRequest>() }
                response {
                    code(HttpStatusCode.OK) { body<LoginResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                    code(HttpStatusCode.BadRequest) { body<ErrorResponse>() }
                }
            }) {
                val req = call.receive<TotpRecoveryRequest>()
                val userId = verifyTotpTempToken(jwtManager, req.tempToken)
                    ?: run {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or expired login"))
                        return@post
                    }

                val user = userRepository.findById(userId)
                val record = user?.let { userRepository.findCredentials(it.email) }
                if (record == null || !record.isActive) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("User not found or inactive"))
                    return@post
                }
                if (!record.totpEnabled) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("TOTP is not enabled"))
                    return@post
                }

                val codeHash = totpService.hashRecoveryCode(req.code.trim())
                if (!recoveryCodeRepository.consumeCode(userId, codeHash)) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid recovery code"))
                    return@post
                }

                val groups = userRepository.getUserGlobalGroups(userId)
                    .map { it.groupName }
                call.respond(
                    call.issueSessionCookies(
                        jwtManager = jwtManager,
                        refreshTokenService = refreshTokenService,
                        userId = userId,
                        username = record.username,
                        email = record.email,
                        groupNames = groups,
                        mustChangePassword = record.mustChangePassword,
                        secureCookies = secureCookies,
                        cookieDomainOrNull = cookieDomainOrNull
                    )
                )
            }
        } // rateLimit auth-totp-verify

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

                val clientFingerprint = call.request.headers["X-Device-Fingerprint"]
                if (!refreshTokenService.isTrustedAndFingerprintValid(rawToken, clientFingerprint ?: "")) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Device not recognized"))
                    return@post
                }

                val (userId, _, _) = refreshTokenService.rotate(rawToken)
                    ?: run {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or expired refresh token"))
                        return@post
                    }

                val userInfo = lookupUserById(userRepository, userId)
                    ?: run {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("User not found or inactive"))
                        return@post
                    }

                call.respond(
                    call.issueSessionCookies(
                        jwtManager = jwtManager,
                        refreshTokenService = refreshTokenService,
                        userId = userId,
                        username = userInfo.username,
                        email = userInfo.email,
                        groupNames = userInfo.groupNames,
                        mustChangePassword = userInfo.mustChangePassword,
                        secureCookies = secureCookies,
                        cookieDomainOrNull = cookieDomainOrNull
                    )
                )
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
                    domain = cookieDomainOrNull,
                    maxAge = 0
                )
                call.respond(HttpStatusCode.NoContent)
            }

            post("/logout-all", {
                operationId = "authLogoutAll"
                summary = "Logout all sessions except the current one"
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val currentRefreshToken = call.request.cookies["refresh_token"]
                if (currentRefreshToken != null) {
                    refreshTokenService.revokeAllExceptCurrent(userId, currentRefreshToken)
                }
                else {
                    refreshTokenService.revokeAll(userId)
                }
                call.respond(HttpStatusCode.NoContent)
            }

            post("/change-password", {
                operationId = "authChangePassword"
                summary = "Change password"
                request { body<ChangePasswordRequest>() }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.BadRequest) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val req = call.receive<ChangePasswordRequest>()

                val userInfo = lookupUserById(userRepository, userId)
                    ?: run {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("User not found"))
                        return@post
                    }

                val credentials = userRepository.findCredentials(userInfo.email)!!

                if (!userInfo.mustChangePassword) {
                    val passwordOk = Argon2Hasher.verify(req.oldPassword, credentials.passwordHash)
                    if (!passwordOk) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Current password is incorrect"))
                        return@post
                    }
                }

                val newHash = Argon2Hasher.hash(req.newPassword)
                userRepository.updatePassword(userId, newHash)
                userRepository.setMustChangePassword(userId, false)

                refreshTokenService.revokeAll(userId)

                val refreshResult = refreshTokenService.issue(userId)
                call.response.cookies.append(
                    name = "refresh_token",
                    value = refreshResult.rawToken,
                    httpOnly = true,
                    secure = secureCookies,
                    extensions = mapOf("SameSite" to "Strict"),
                    path = "/api/auth",
                    domain = cookieDomainOrNull
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

            // --- TOTP management (authenticated) ---

            get("/totp/status", {
                operationId = "authTotpStatus"
                summary = "Get TOTP status for current user"
                response {
                    code(HttpStatusCode.OK) { body<TotpStatusResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val user = userRepository.findById(userId)
                    ?: run {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("User not found"))
                        return@get
                    }
                call.respond(
                    TotpStatusResponse(
                        enabled = user.totpEnabled,
                        recoveryCodesRemaining = recoveryCodeRepository.countRemaining(userId)
                    )
                )
            }

            post("/totp/setup", {
                operationId = "authTotpSetup"
                summary = "Generate a new TOTP secret, QR code, and recovery codes (enabled after verification)"
                response {
                    code(HttpStatusCode.OK) { body<TotpSetupResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val user = userRepository.findById(userId)
                    ?: run {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("User not found"))
                        return@post
                    }
                if (user.totpEnabled) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("TOTP is already enabled"))
                    return@post
                }

                val secret = totpService.generateSecret()
                val recoveryCodes = totpService.generateRecoveryCodes()

                userRepository.storeTotpSecret(userId, totpService.encryptSecret(secret))
                recoveryCodeRepository.deleteAll(userId)
                recoveryCodeRepository.insert(userId, recoveryCodes.map { totpService.hashRecoveryCode(it) })

                call.respond(
                    TotpSetupResponse(
                        secret = secret,
                        qrDataUri = totpService.createQrCodeDataUri(secret, user.email),
                        recoveryCodes = recoveryCodes
                    )
                )
            }

            post("/totp/enable", {
                operationId = "authTotpEnable"
                summary = "Verify a TOTP code and enable TOTP for current user"
                request { body<TotpEnableRequest>() }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.BadRequest) { body<ErrorResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val user = userRepository.findById(userId)
                    ?: run {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("User not found"))
                        return@post
                    }
                if (user.totpEnabled) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("TOTP is already enabled"))
                    return@post
                }

                val storedSecret = userRepository.findTotpSecret(userId)
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Run setup first"))
                        return@post
                    }
                val secret = runCatching { totpService.decryptSecret(storedSecret) }.getOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Could not read TOTP secret"))
                        return@post
                    }

                val code = call.receive<TotpEnableRequest>().code.trim()
                if (!totpService.validate(secret, code)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid verification code"))
                    return@post
                }

                userRepository.enableTotp(userId)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/totp/disable", {
                operationId = "authTotpDisable"
                summary = "Verify a TOTP code and disable TOTP for current user"
                request { body<TotpDisableRequest>() }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.BadRequest) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                if (userRepository.findById(userId) == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("User not found"))
                    return@post
                }
                val storedSecret = userRepository.findTotpSecret(userId)
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("TOTP is not enabled"))
                        return@post
                    }
                val secret = runCatching { totpService.decryptSecret(storedSecret) }.getOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Could not read TOTP secret"))
                        return@post
                    }

                val code = call.receive<TotpDisableRequest>().code.trim()
                if (!totpService.validate(secret, code)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid verification code"))
                    return@post
                }

                userRepository.disableTotp(userId)
                recoveryCodeRepository.deleteAll(userId)
                refreshTokenService.revokeAllTrusted(userId)
                call.respond(HttpStatusCode.NoContent)
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

                val userInfo = lookupUserById(userRepository, userId)
                    ?: run {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("User not found or inactive"))
                        return@get
                    }

                val permissions = PermissionResolver.resolve(userId)
                    .toList()
                    .sorted()

                val serverPermissions = PermissionResolver.serverPermissions(userId)
                    .mapKeys { it.key.toString() }
                    .mapValues { (_, perms) ->
                        perms.toList()
                            .sorted()
                    }

                call.respond(
                    MeResponse(
                        id = userId.toString(),
                        username = userInfo.username,
                        email = userInfo.email,
                        groups = userInfo.groupNames,
                        permissions = permissions,
                        serverPermissions = serverPermissions,
                        totpEnabled = userRepository.findById(userId)?.totpEnabled ?: false,
                        mustChangePassword = userInfo.mustChangePassword
                    )
                )
            }
        }
    }
}
