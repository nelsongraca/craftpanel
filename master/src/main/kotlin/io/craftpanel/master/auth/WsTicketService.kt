package io.craftpanel.master.auth

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

class WsTicketService {
    private val random = SecureRandom()
    private val ticketTtl = 30.seconds

    private data class TicketRecord(val userId: UUID, val expiresAt: Instant)
    private val tickets = ConcurrentHashMap<String, TicketRecord>()

    fun issue(userId: UUID): Pair<String, Int> {
        val raw = generateRaw()
        val expiresAt = Clock.System.now().plus(ticketTtl)
        tickets[raw] = TicketRecord(userId, expiresAt)
        return raw to ticketTtl.inWholeSeconds.toInt()
    }

    fun consume(rawTicket: String): UUID? {
        val now = Clock.System.now()
        tickets.entries.removeIf { it.value.expiresAt < now }
        val record = tickets.remove(rawTicket) ?: return null
        if (record.expiresAt < now) return null
        return record.userId
    }

    private fun generateRaw(): String {
        val bytes = ByteArray(48).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
