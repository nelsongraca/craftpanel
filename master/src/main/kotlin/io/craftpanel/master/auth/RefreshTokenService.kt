package io.craftpanel.master.auth

import io.craftpanel.master.database.schema.RefreshTokens
import io.craftpanel.master.database.schema.Users
import io.craftpanel.master.util.CryptoUtils
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

data class RefreshTokenResult(
    val rawToken: String,
    val expiresAt: LocalDateTime,
)

class RefreshTokenService {

    private val tokenLifetime = 30.days

    fun issue(userId: Uuid): RefreshTokenResult = transaction {
        val rawToken = generateRaw()
        val hash = sha256Hex(rawToken)
        val expiresAt = Clock.System.now()
            .plus(tokenLifetime)
            .toLocalDateTime(TimeZone.UTC)

        RefreshTokens.insert {
            it[RefreshTokens.userId] = userId
            it[RefreshTokens.tokenHash] = hash
            it[RefreshTokens.expiresAt] = expiresAt
            it[RefreshTokens.revoked] = false
        }

        RefreshTokenResult(rawToken, expiresAt)
    }

    fun rotate(rawToken: String): Pair<Uuid, RefreshTokenResult>? = transaction {
        val hash = sha256Hex(rawToken)
        val now = Clock.System.now()
            .toLocalDateTime(TimeZone.UTC)

        val row = RefreshTokens.selectAll()
            .where {
                (RefreshTokens.tokenHash eq hash) and
                        (RefreshTokens.revoked eq false) and
                        (RefreshTokens.expiresAt greater now)
            }
            .firstOrNull() ?: return@transaction null

        val userId = row[RefreshTokens.userId]

        val userActive = Users.selectAll()
            .where { Users.id eq userId }
            .firstOrNull()
            ?.get(Users.isActive) ?: false

        if (!userActive) return@transaction null

        RefreshTokens.update({ RefreshTokens.tokenHash eq hash }) {
            it[RefreshTokens.revoked] = true
        }

        val newToken = issue(userId)
        Pair(userId, newToken)
    }

    fun revoke(rawToken: String): Unit = transaction {
        val hash = sha256Hex(rawToken)
        RefreshTokens.update({ RefreshTokens.tokenHash eq hash }) {
            it[RefreshTokens.revoked] = true
        }
    }

    fun revokeAll(userId: Uuid): Unit = transaction {
        RefreshTokens.update({ RefreshTokens.userId eq userId }) {
            it[RefreshTokens.revoked] = true
        }
    }

    private fun generateRaw(): String = CryptoUtils.generateToken(48)

    private fun sha256Hex(input: String): String =
        // Result is used only as a DB lookup key (SQL WHERE) — never compared in Kotlin code.
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.toByteArray()))
}
