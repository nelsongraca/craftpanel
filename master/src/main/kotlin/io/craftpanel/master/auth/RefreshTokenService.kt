package io.craftpanel.master.auth

import io.craftpanel.master.database.schema.RefreshTokens
import io.craftpanel.master.database.schema.Users
import io.craftpanel.master.service.repo.UserRepository
import io.craftpanel.master.util.CryptoUtils
import kotlinx.datetime.*
import kotlinx.datetime.TimeZone
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.MessageDigest
import java.util.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class RefreshTokenResult(val rawToken: String, val expiresAt: LocalDateTime)

class RefreshTokenService(private val userRepository: UserRepository) {

    private val tokenLifetime = 30.days

    fun issue(userId: Uuid): RefreshTokenResult {
        val rawToken = generateRaw()
        val hash = sha256Hex(rawToken)
        val expiresAt = Clock.System.now()
            .plus(tokenLifetime)

        transaction {
            RefreshTokens.insert {
                it[RefreshTokens.userId] = EntityID(userId, Users)
                it[RefreshTokens.tokenHash] = hash
                it[RefreshTokens.expiresAt] = expiresAt.toLocalDateTime(TimeZone.UTC)
            }
        }

        return RefreshTokenResult(rawToken, expiresAt.toLocalDateTime(TimeZone.UTC))
    }

    fun rotate(rawToken: String): Pair<Uuid, RefreshTokenResult>? {
        val hash = sha256Hex(rawToken)
        val now = Clock.System.now()

        val row = userRepository.findRefreshTokenByHash(hash) ?: return null

        if (row.revoked || Instant.parse(row.expiresAt) <= now) return null

        val userId = row.userId

        if (!userRepository.isActive(userId)) return null

        val rawNewToken = generateRaw()
        val newHash = sha256Hex(rawNewToken)
        val newExpiresAt = now.plus(tokenLifetime)

        transaction {
            RefreshTokens.update({ RefreshTokens.tokenHash eq hash }) { it[RefreshTokens.revoked] = true }
            RefreshTokens.insert {
                it[RefreshTokens.userId] = EntityID(userId, Users)
                it[RefreshTokens.tokenHash] = newHash
                it[RefreshTokens.expiresAt] = newExpiresAt.toLocalDateTime(TimeZone.UTC)
            }
        }

        return Pair(userId, RefreshTokenResult(rawNewToken, newExpiresAt.toLocalDateTime(TimeZone.UTC)))
    }

    fun revoke(rawToken: String) {
        val hash = sha256Hex(rawToken)
        transaction { RefreshTokens.update({ RefreshTokens.tokenHash eq hash }) { it[RefreshTokens.revoked] = true } }
    }

    fun revokeAll(userId: Uuid) {
        transaction { RefreshTokens.update({ RefreshTokens.userId eq userId }) { it[RefreshTokens.revoked] = true } }
    }

    private fun generateRaw(): String = CryptoUtils.generateToken(48)

    private fun sha256Hex(input: String): String = // Result is used only as a DB lookup key (SQL WHERE) — never compared in Kotlin code.
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(input.toByteArray())
            )
}
