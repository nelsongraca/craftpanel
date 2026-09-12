package io.craftpanel.master.auth

import io.craftpanel.master.database.schema.TrustedDevices
import io.craftpanel.master.database.schema.Users
import io.craftpanel.master.service.repo.UserRepository
import io.craftpanel.master.util.CryptoUtils
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

class TrustedDeviceService(private val userRepository: UserRepository) {

    private val deviceTrustLifetime = 30.days

    fun issue(userId: Uuid, deviceFingerprint: String, userAgent: String): String {
        val rawToken = generateRaw()
        val hash = sha256Hex(rawToken)
        val expiresAt = Clock.System.now().plus(deviceTrustLifetime)

        transaction {
            TrustedDevices.insert {
                it[TrustedDevices.userId] = EntityID(userId, Users)
                it[TrustedDevices.tokenHash] = hash
                it[TrustedDevices.deviceFingerprint] = deviceFingerprint
                it[TrustedDevices.userAgent] = userAgent
                it[TrustedDevices.expiresAt] = expiresAt.toLocalDateTime(TimeZone.UTC)
            }
        }

        return rawToken
    }

    fun isValid(
        userId: Uuid,
        rawToken: String,
        deviceFingerprint: String,
        userAgent: String,
    ): Boolean {
        val hash = sha256Hex(rawToken)
        val row = userRepository.findTrustedDevice(userId, hash, deviceFingerprint, userAgent) ?: return false
        if (row.revoked) return false
        return true
    }

    fun revokeAll(userId: Uuid) {
        transaction {
            TrustedDevices.update({ TrustedDevices.userId eq userId }) {
                it[TrustedDevices.revoked] = true
            }
        }
    }

    private fun generateRaw(): String = CryptoUtils.generateToken(48)

    private fun sha256Hex(input: String): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        )
}