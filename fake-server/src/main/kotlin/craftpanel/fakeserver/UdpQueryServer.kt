package craftpanel.fakeserver

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer

/**
 * Implements the Minecraft Query Protocol (UDP, port 25565).
 *
 * This is the protocol the agent currently uses via CLI to get the player list.
 * The query protocol has two phases:
 *   1. Handshake — client sends magic + type 9, server responds with a challenge token
 *   2. Stat request — client sends magic + type 0 + session + token, server responds with stats
 *
 * Two stat response formats exist: basic (just MOTD/players/etc) and full (includes plugins).
 * We implement full stat as it includes the player list by name.
 *
 * Protocol reference: https://wiki.vg/Query
 */
class UdpQueryServer(private val config: Config) {

    // In-memory challenge token store: sessionId -> token
    // Tokens are 32-bit integers; in production servers these rotate on a timer,
    // but for a test fixture we just issue one per session and keep it until used.
    private val challenges = mutableMapOf<Int, Int>()

    fun start() {
        val socket = DatagramSocket(config.gamePort)
        log("UDP query server listening on :${config.gamePort}")
        val buf = ByteArray(1024)

        while (true) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
                val response = handlePacket(packet.data, packet.length) ?: continue
                val responsePacket = DatagramPacket(response, response.size, packet.address, packet.port)
                socket.send(responsePacket)
            } catch (e: Exception) {
                log("UDP query error: ${e.message}")
            }
        }
    }

    private fun handlePacket(data: ByteArray, length: Int): ByteArray? {
        if (length < 7) return null

        val buf = ByteBuffer.wrap(data, 0, length)

        // Magic: 0xFEFD
        val magic = buf.short.toInt() and 0xFFFF
        if (magic != 0xFEFD) return null

        val type      = buf.get().toInt() and 0xFF
        val sessionId = buf.int and 0x0F0F0F0F  // Minecraft masks the session id

        return when (type) {
            0x09 -> handleHandshake(sessionId)
            0x00 -> handleStat(sessionId, buf, length)
            else -> null
        }
    }

    /**
     * Handshake response: type(1) + sessionId(4) + challengeToken(string, null-terminated)
     */
    private fun handleHandshake(sessionId: Int): ByteArray {
        val token = (Math.random() * Int.MAX_VALUE).toInt()
        challenges[sessionId] = token

        val tokenStr = token.toString().toByteArray(Charsets.US_ASCII)
        return buildPacket(0x09, sessionId) {
            it.put(tokenStr)
            it.put(0x00)  // null terminator
        }
    }

    /**
     * Stat response — we always return the full stat (11-byte padding in request = full stat).
     * Basic stat (7-byte request) also handled by falling through to full stat response —
     * clients that only ask for basic still get full; that's fine.
     */
    private fun handleStat(sessionId: Int, buf: ByteBuffer, length: Int): ByteArray? {
        if (buf.remaining() < 4) return null

        val challengeToken = buf.int
        val expected = challenges[sessionId] ?: return null
        if (challengeToken != expected) return null

        challenges.remove(sessionId)

        return buildFullStat(sessionId)
    }

    /**
     * Full stat response format:
     *   type(1) + sessionId(4) + padding(11 bytes: "splitnum\x00\x80\x00")
     *   + key-value pairs (null-terminated strings, pairs separated by \x00, list ended by \x00\x00)
     *   + padding(10 bytes: "\x01player_\x00\x00")
     *   + player names (null-terminated strings, list ended by \x00)
     */
    private fun buildFullStat(sessionId: Int): ByteArray {
        val online = config.onlinePlayers.size

        val kvPairs = mapOf(
            "hostname"   to config.motd,
            "gametype"   to "SMP",
            "game_id"    to "MINECRAFT",
            "version"    to "1.21.4",
            "plugins"    to "",
            "map"        to "world",
            "numplayers" to online.toString(),
            "maxplayers" to config.maxPlayers.toString(),
            "hostport"   to config.gamePort.toString(),
            "hostip"     to "0.0.0.0",
        )

        return buildPacket(0x00, sessionId) { out ->
            // KV section padding
            out.put("splitnum\u0000\u0080\u0000".toByteArray(Charsets.ISO_8859_1))

            // Key-value pairs
            for ((k, v) in kvPairs) {
                out.put(k.toByteArray(Charsets.UTF_8))
                out.put(0x00)
                out.put(v.toByteArray(Charsets.UTF_8))
                out.put(0x00)
            }
            out.put(0x00)  // end of KV section

            // Player list section padding
            out.put("\u0001player_\u0000\u0000".toByteArray(Charsets.ISO_8859_1))

            // Player names
            for (player in config.onlinePlayers) {
                out.put(player.toByteArray(Charsets.UTF_8))
                out.put(0x00)
            }
            out.put(0x00)  // end of player list
        }
    }

    private fun buildPacket(type: Int, sessionId: Int, body: (ByteBuffer) -> Unit): ByteArray {
        // Pre-allocate generously — player names + KV pairs won't exceed this for test data
        val buf = ByteBuffer.allocate(4096)
        buf.put(type.toByte())
        buf.putInt(sessionId)
        body(buf)
        return buf.array().copyOf(buf.position())
    }
}
