package io.craftpanel.agent

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

object McQueryClient {

    data class QueryResult(val playerCount: Int, val playerNames: List<String>)

    fun query(host: String, port: Int = 25565, timeoutMs: Int = 3000): QueryResult? {
        return runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                val address = InetAddress.getByName(host)

                val sessionId = (Math.random() * 0x0FFFFFFF).toInt() and 0x0F0F0F0F
                val token = handshake(socket, address, port, sessionId) ?: return null
                fullStat(socket, address, port, sessionId, token)
            }
        }.getOrElse { null }
    }

    private fun handshake(socket: DatagramSocket, address: InetAddress, port: Int, sessionId: Int): Int? {
        val request = ByteBuffer.allocate(7)
        request.putShort(0xFEFD.toShort())
        request.put(0x09.toByte())
        request.putInt(sessionId)

        send(socket, address, port, request.array())

        val buf = ByteArray(1024)
        val response = DatagramPacket(buf, buf.size)
        socket.receive(response)

        val rb = ByteBuffer.wrap(buf, 0, response.length)
        if (rb.get()
                .toInt() and 0xFF != 0x09
        ) return null
        rb.getInt() // session id echo

        // challenge token is a null-terminated ASCII decimal integer
        val sb = StringBuilder()
        while (rb.hasRemaining()) {
            val b = rb.get()
            if (b == 0.toByte()) break
            sb.append(
                b.toInt()
                    .toChar()
            )
        }
        return sb.toString()
            .trim()
            .toIntOrNull()
    }

    private fun fullStat(socket: DatagramSocket, address: InetAddress, port: Int, sessionId: Int, token: Int): QueryResult? {
        val request = ByteBuffer.allocate(15)
        request.putShort(0xFEFD.toShort())
        request.put(0x00.toByte())
        request.putInt(sessionId)
        request.putInt(token)
        request.putInt(0) // full stat padding

        send(socket, address, port, request.array())

        val buf = ByteArray(4096)
        val response = DatagramPacket(buf, buf.size)
        socket.receive(response)

        val rb = ByteBuffer.wrap(buf, 0, response.length)
        if (rb.get()
                .toInt() and 0xFF != 0x00
        ) return null
        rb.getInt() // session id

        // skip 11-byte KV section padding ("splitnum\0\x80\0")
        repeat(11) { rb.get() }

        // read KV pairs until double-null
        val kv = mutableMapOf<String, String>()
        while (rb.hasRemaining()) {
            val key = readNullTerminated(rb)
            if (key.isEmpty()) break
            val value = readNullTerminated(rb)
            kv[key] = value
        }

        // skip 10-byte player list padding ("\x01player_\0\0")
        repeat(10) { if (rb.hasRemaining()) rb.get() }

        // read player names until empty entry
        val players = mutableListOf<String>()
        while (rb.hasRemaining()) {
            val name = readNullTerminated(rb)
            if (name.isEmpty()) break
            players.add(name)
        }

        val count = kv["numplayers"]?.toIntOrNull() ?: players.size
        return QueryResult(count, players)
    }

    private fun readNullTerminated(buf: ByteBuffer): String {
        val sb = StringBuilder()
        while (buf.hasRemaining()) {
            val b = buf.get()
            if (b == 0.toByte()) break
            sb.append(
                b.toInt()
                    .and(0xFF)
                    .toChar()
            )
        }
        return sb.toString()
    }

    private fun send(socket: DatagramSocket, address: InetAddress, port: Int, data: ByteArray) {
        socket.send(DatagramPacket(data, data.size, address, port))
    }
}
