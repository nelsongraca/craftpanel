package io.craftpanel.master.auth

import io.craftpanel.master.database.schema.RefreshTokens
import io.craftpanel.master.database.schema.Users
import io.craftpanel.master.util.toJavaUuid
import io.craftpanel.master.util.toKotlinUuid
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlin.time.Duration.Companion.days

data class RefreshTokenResult(
    val rawToken: String,
    val expiresAt: LocalDateTime,
)

class RefreshTokenService {
    private val random = SecureRandom()
    private val tokenLifetime = 30.days

    fun issue(userId: UUID): RefreshTokenResult = transaction {
        val rawToken = generateRaw()
        val hash = sha256Hex(rawToken)
        val expiresAt = Clock.System.now().plus(tokenLifetime)
            .toLocalDateTime(TimeZone.UTC)

        RefreshTokens.insert {
            it[RefreshTokens.userId] = userId.toKotlinUuid()
            it[RefreshTokens.tokenHash] = hash
            it[RefreshTokens.expiresAt] = expiresAt
            it[RefreshTokens.revoked] = false
        }

        RefreshTokenResult(rawToken, expiresAt)
    }

    fun rotate(rawToken: String): Pair<UUID, RefreshTokenResult>? = transaction {
        val hash = sha256Hex(rawToken)
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

        val row = RefreshTokens.selectAll()
            .where {
                (RefreshTokens.tokenHash eq hash) and
                (RefreshTokens.revoked eq false) and
                (RefreshTokens.expiresAt greater now)
            }
            .firstOrNull() ?: return@transaction null

        val userKotlinId = row[RefreshTokens.userId]

        val userActive = Users.selectAll()
            .where { Users.id eq userKotlinId }
            .firstOrNull()
            ?.get(Users.isActive) ?: false

        if (!userActive) return@transaction null

        RefreshTokens.update({ RefreshTokens.tokenHash eq hash }) {
            it[RefreshTokens.revoked] = true
        }

        val userId = userKotlinId.toJavaUuid()
        val newToken = issue(userId)
        Pair(userId, newToken)
    }

    fun revokeAll(userId: UUID): Unit = transaction {
        val kotlinId = userId.toKotlinUuid()
        RefreshTokens.deleteWhere { RefreshTokens.userId eq kotlinId }
    }

    private fun generateRaw(): String {
        val bytes = ByteArray(48).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
