package io.craftpanel.master.auth

import io.craftpanel.master.util.CryptoUtils
import kotlin.uuid.Uuid
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class WsTicketService {

    private val ticketTtl = 30.seconds

    private data class TicketRecord(val userId: Uuid, val expiresAt: Instant)

    private val tickets = ConcurrentHashMap<String, TicketRecord>()

    fun issue(userId: Uuid): Pair<String, Int> {
        val raw = generateRaw()
        val expiresAt = Clock.System.now()
            .plus(ticketTtl)
        tickets[raw] = TicketRecord(userId, expiresAt)
        return raw to ticketTtl.inWholeSeconds.toInt()
    }

    fun consume(rawTicket: String): Uuid? {
        val now = Clock.System.now()
        tickets.entries.removeIf { it.value.expiresAt < now }
        val record = tickets.remove(rawTicket) ?: return null
        if (record.expiresAt < now) return null
        return record.userId
    }

    private fun generateRaw(): String = CryptoUtils.generateToken(48)
}
