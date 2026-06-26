package io.craftpanel.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

object McStatusClient {

    private val log = LoggerFactory.getLogger(McStatusClient::class.java)

    data class StatusResult(val playerCount: Int, val playerNames: List<String>)

    fun ping(host: String, port: Int = 25565, timeoutMs: Int = 3000, serverAddress: String = host): StatusResult? =
        runCatching {
            Socket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                val out = DataOutputStream(
                    socket.getOutputStream()
                        .buffered()
                )
                val inp = DataInputStream(
                    socket.getInputStream()
                        .buffered()
                )

                // Handshake (0x00): protocolVersion=-1, serverAddress (routing hostname), port, nextState=1
                val handshake = buildPacket { buf ->
                    writeVarInt(buf, -1)
                    writeString(buf, serverAddress)
                    buf.write(port shr 8 and 0xFF)
                    buf.write(port and 0xFF)
                    writeVarInt(buf, 1)
                }
                writePacket(out, handshake)
                // Status request (0x00, empty)
                writePacket(out, buildPacket {})
                out.flush()

                // Read length-prefixed response
                readVarInt(inp) // total packet length
                val packetId = readVarInt(inp)
                if (packetId != 0x00) return null
                val jsonLen = readVarInt(inp)
                val jsonBytes = ByteArray(jsonLen)
                inp.readFully(jsonBytes)

                val root = Json.parseToJsonElement(String(jsonBytes, Charsets.UTF_8)).jsonObject
                val players = root["players"]?.jsonObject
                val online = players?.get("online")?.jsonPrimitive?.int ?: 0
                val names = players?.get("sample")?.jsonArray
                    ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                    ?: emptyList()
                StatusResult(online, names)
            }
        }.getOrElse {
            log.debug("Status ping to $host:$port failed: ${it.message}")
            null
        }

    private fun buildPacket(fill: (ByteArrayOutputStream) -> Unit): ByteArray {
        val buf = ByteArrayOutputStream()
        writeVarInt(buf, 0x00)
        fill(buf)
        return buf.toByteArray()
    }

    private fun writePacket(out: DataOutputStream, data: ByteArray) {
        writeVarInt(out, data.size)
        out.write(data)
    }

    private fun writeVarInt(out: java.io.OutputStream, value: Int) {
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0) {
                out.write(v)
                return
            }
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
    }

    private fun readVarInt(inp: DataInputStream): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = inp.readByte()
                .toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift >= 35) throw RuntimeException("VarInt too large")
        }
    }

    private fun writeString(out: java.io.OutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeVarInt(out, bytes.size)
        out.write(bytes)
    }
}
