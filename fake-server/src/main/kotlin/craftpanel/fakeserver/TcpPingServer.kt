package craftpanel.fakeserver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import java.net.Socket
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Handles Minecraft Java Edition server list ping (protocol 1.7+).
 * Clients send a handshake + status request; we respond with a JSON status payload.
 * This is what the Minecraft client uses when adding a server to its list.
 *
 * The agent currently uses CLI + query protocol for player counts, but the TCP ping
 * is needed so the agent doesn't immediately report the port as unreachable.
 */
class TcpPingServer(private val config: Config) {

    fun start() {
        val serverSocket = ServerSocket(config.gamePort)
        log("TCP ping server listening on :${config.gamePort}")
        while (true) {
            val client = serverSocket.accept()
            // Handle each connection on its own — we don't need coroutine scope here,
            // plain thread-per-connection is fine for a test fixture with handful of connections
            Thread { handleClient(client) }.start()
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 5_000
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            // Read the first packet — could be handshake (0x00) or legacy ping (0xFE)
            val firstByte = input.read()
            if (firstByte == -1) return

            when (firstByte) {
                0xFE -> handleLegacyPing(output)
                else -> handleModernHandshake(firstByte, input, output)
            }
        } catch (_: Exception) {
            // Clients frequently drop mid-handshake — not an error
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Legacy ping (pre-1.7 clients, still sent by some tools).
     * Response format: §1\0<protocol>\0<version>\0<motd>\0<online>\0<max>
     */
    private fun handleLegacyPing(output: DataOutputStream) {
        val online = config.onlinePlayers.size
        val response = "§1\u0000127\u00001.21.4\u0000${config.motd}\u0000$online\u0000${config.maxPlayers}"
        val chars = response.toCharArray()

        output.writeByte(0xFF)
        output.writeShort(chars.size)
        for (c in chars) output.writeShort(c.code)
        output.flush()
    }

    /**
     * Modern status ping (1.7+).
     * Handshake packet → Status Request → Status Response (JSON) → optional Ping/Pong
     */
    private fun handleModernHandshake(firstByte: Int, input: DataInputStream, output: DataOutputStream) {
        // We already consumed the first byte; reconstruct minimal packet reading
        // For our purposes we just need to consume the handshake and respond with status JSON
        // Full VarInt/packet framing is implemented minimally — just enough to respond correctly

        try {
            // Drain the rest of the handshake packet (we don't care about its contents)
            val packetLength = readVarInt(firstByte, input)
            if (packetLength > 0) {
                val buf = ByteArray(packetLength)
                input.readFully(buf)
            }

            // Read status request packet (should be length=1, id=0x00)
            readVarInt(input)  // packet length
            readVarInt(input)  // packet id — should be 0x00

            // Send status response
            val json = buildStatusJson()
            val jsonBytes = json.toByteArray(Charsets.UTF_8)

            // Packet: VarInt(length) + VarInt(0x00) + VarInt(jsonLength) + json
            val packetId = varIntBytes(0x00)
            val jsonLen  = varIntBytes(jsonBytes.size)
            val payload  = packetId + jsonLen + jsonBytes
            val length   = varIntBytes(payload.size)

            output.write(length + payload)
            output.flush()

            // Read and respond to ping packet if sent
            runCatching {
                readVarInt(input) // length
                readVarInt(input) // id (0x01)
                val pingPayload = input.readLong()

                val pongId      = varIntBytes(0x01)
                val pongPayload = longToBytes(pingPayload)
                val pongPacket  = pongId + pongPayload
                output.write(varIntBytes(pongPacket.size) + pongPacket)
                output.flush()
            }
        } catch (_: Exception) { /* client disconnected early */ }
    }

    private fun buildStatusJson(): String {
        val online = config.onlinePlayers.size
        val samplePlayers = config.onlinePlayers.take(10).joinToString(",") { name ->
            """{"name":"$name","id":"00000000-0000-0000-0000-000000000000"}"""
        }
        return """
            {
              "version": {"name": "1.21.4", "protocol": 769},
              "players": {
                "max": ${config.maxPlayers},
                "online": $online,
                "sample": [$samplePlayers]
              },
              "description": {"text": "${config.motd}"},
              "enforcesSecureChat": false
            }
        """.trimIndent()
    }

    // --- VarInt helpers ---

    private fun readVarInt(input: DataInputStream): Int = readVarInt(input.read(), input)

    private fun readVarInt(firstByte: Int, input: DataInputStream): Int {
        var value = 0
        var position = 0
        var current = firstByte
        while (true) {
            value = value or ((current and 0x7F) shl position)
            if ((current and 0x80) == 0) break
            position += 7
            if (position >= 32) throw RuntimeException("VarInt too big")
            current = input.read()
        }
        return value
    }

    private fun varIntBytes(value: Int): ByteArray {
        var v = value
        val buf = mutableListOf<Byte>()
        do {
            var temp = (v and 0x7F).toByte()
            v = v ushr 7
            if (v != 0) temp = (temp.toInt() or 0x80).toByte()
            buf.add(temp)
        } while (v != 0)
        return buf.toByteArray()
    }

    private fun longToBytes(v: Long): ByteArray {
        val buf = ByteArray(8)
        for (i in 7 downTo 0) buf[i] = (v shr ((7 - i) * 8)).toByte()
        return buf
    }
}
