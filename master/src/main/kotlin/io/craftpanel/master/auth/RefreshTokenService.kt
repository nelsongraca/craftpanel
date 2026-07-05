package io.craftpanel.master.auth

import io.craftpanel.master.service.repo.UserRepository
import io.craftpanel.master.util.CryptoUtils
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

data class RefreshTokenResult(val rawToken: String, val expiresAt: LocalDateTime)

class RefreshTokenService(private val userRepository: UserRepository) {

    private val tokenLifetime = 30.days

    fun issue(userId: Uuid): RefreshTokenResult {
        val rawToken = generateRaw()
        val hash = sha256Hex(rawToken)
        val expiresAt = Clock.System.now()
            .plus(tokenLifetime)

        userRepository.issueRefreshToken(userId, hash, expiresAt)

        return RefreshTokenResult(rawToken, expiresAt.toLocalDateTime(TimeZone.UTC))
    }

    fun rotate(rawToken: String): Pair<Uuid, RefreshTokenResult>? {
        val hash = sha256Hex(rawToken)
        val now = Clock.System.now()

        val row = userRepository.findRefreshTokenByHash(hash) ?: return null

        if (row.revoked || LocalDateTime.parse(row.expiresAt) <= now.toLocalDateTime(TimeZone.UTC)) return null

        val userId = row.userId

        if (!userRepository.isActive(userId)) return null

        val rawNewToken = generateRaw()
        val newHash = sha256Hex(rawNewToken)
        val newExpiresAt = now.plus(tokenLifetime)

        userRepository.rotateRefreshToken(hash, newHash, newExpiresAt, userId)

        return Pair(userId, RefreshTokenResult(rawNewToken, newExpiresAt.toLocalDateTime(TimeZone.UTC)))
    }

    fun revoke(rawToken: String) {
        val hash = sha256Hex(rawToken)
        userRepository.revokeRefreshToken(hash)
    }

    fun revokeAll(userId: Uuid) {
        userRepository.revokeAllRefreshTokens(userId)
    }

    private fun generateRaw(): String = CryptoUtils.generateToken(48)

    private fun sha256Hex(input: String): String = // Result is used only as a DB lookup key (SQL WHERE) — never compared in Kotlin code.
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.toByteArray()))
}
