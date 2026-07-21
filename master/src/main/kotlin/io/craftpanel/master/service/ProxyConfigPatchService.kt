package io.craftpanel.master.service

import io.craftpanel.master.service.repo.ProxyBackendRepository
import io.craftpanel.master.service.repo.ProxyBackendRow
import io.craftpanel.master.service.repo.ServerRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid

class ProxyConfigPatchService(private val proxyBackendRepository: ProxyBackendRepository, private val serverRepository: ServerRepository) {
    fun generatePatch(proxyServerId: Uuid): String {
        val serverRow = serverRepository.findById(proxyServerId)
            ?: throw NotFoundException("Server not found")
        if (serverRow.serverType !in PROXY_SERVER_TYPES) {
            throw ConflictException("Server is not a proxy type")
        }

        val backends = proxyBackendRepository.listProxyBackends(proxyServerId)
            .sortedBy { it.order }

        val ops = mutableListOf<JsonObject>()

        ops.add(serversOp(serverRow.serverType, backends))
        if (serverRow.serverType != "VELOCITY" && backends.isNotEmpty()) {
            ops.add(prioritiesOp(backends))
        }
        if (serverRow.proxyMotd != null) {
            ops.add(motdOp(serverRow.serverType, serverRow.proxyMotd))
        }
        if (serverRow.proxyMaxPlayers != null) {
            ops.add(maxPlayersOp(serverRow.serverType, serverRow.proxyMaxPlayers))
        }
        if (serverRow.proxyForwardingMode != null) {
            ops.add(forwardingModeOp(serverRow.serverType, serverRow.proxyForwardingMode))
        }

        return Json.encodeToString(JsonArray(ops))
    }

    private fun serversOp(serverType: String, backends: List<ProxyBackendRow>): JsonObject {
        val servers = buildMap<String, JsonElement> {
            backends.forEach { b ->
                put(
                    b.backendName,
                    if (serverType == "VELOCITY") {
                        JsonPrimitive("craftpanel-${b.backendServerId}:25565")
                    } else {
                        JsonObject(
                            mapOf(
                                "address" to JsonPrimitive("craftpanel-${b.backendServerId}:25565"),
                                "restricted" to JsonPrimitive(false)
                            )
                        )
                    }
                )
            }
            if (serverType == "VELOCITY") {
                put("try", JsonArray(backends.map { JsonPrimitive(it.backendName) }))
            }
        }
        return patchOp("$.servers", JsonObject(servers))
    }

    private fun prioritiesOp(backends: List<ProxyBackendRow>): JsonObject = patchOp("$.listeners[0].priorities", JsonArray(backends.map { JsonPrimitive(it.backendName) }))

    private fun motdOp(serverType: String, motd: String): JsonObject {
        val path = if (serverType == "VELOCITY") "$.motd" else "$.listeners[0].motd"
        return patchOp(path, JsonPrimitive(motd))
    }

    private fun maxPlayersOp(serverType: String, maxPlayers: Int): JsonObject {
        val (path, valueType) = if (serverType == "VELOCITY") {
            "\$['show-max-players']" to "int"
        } else {
            "$.player_limit" to "int"
        }
        return JsonObject(
            mapOf(
                "op" to JsonPrimitive("\$set"),
                "path" to JsonPrimitive(path),
                "value" to JsonPrimitive(maxPlayers),
                "value-type" to JsonPrimitive(valueType)
            )
        )
    }

    private fun forwardingModeOp(serverType: String, mode: String): JsonObject {
        if (serverType == "VELOCITY") {
            return JsonObject(
                mapOf(
                    "op" to JsonPrimitive("\$set"),
                    "path" to JsonPrimitive("\$['player-info-forwarding-mode']"),
                    "value" to JsonPrimitive(mode.lowercase())
                )
            )
        }
        val ipForward = mode == "LEGACY"
        return JsonObject(
            mapOf(
                "op" to JsonPrimitive("\$set"),
                "path" to JsonPrimitive("$.ip_forward"),
                "value" to JsonPrimitive(ipForward),
                "value-type" to JsonPrimitive("bool")
            )
        )
    }

    private fun patchOp(path: String, value: JsonElement): JsonObject = JsonObject(
        mapOf(
            "op" to JsonPrimitive("\$set"),
            "path" to JsonPrimitive(path),
            "value" to value
        )
    )
}
