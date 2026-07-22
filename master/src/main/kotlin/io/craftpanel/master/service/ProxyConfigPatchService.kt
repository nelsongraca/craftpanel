package io.craftpanel.master.service

import io.craftpanel.master.config.ImagesConfig
import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.service.repo.ProxyBackendRepository
import io.craftpanel.master.service.repo.ProxyBackendRow
import io.craftpanel.master.service.repo.ServerRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid

private const val VELOCITY_FILE = "/server/velocity.toml"
private const val BUNGEE_FILE = "/server/config.yml"

class ProxyConfigPatchService(
    private val proxyBackendRepository: ProxyBackendRepository,
    private val serverRepository: ServerRepository,
    private val images: ImagesConfig = ImagesConfig("itzg/minecraft-server", "itzg/mc-proxy"),
    private val containerNamePrefix: String = "craftpanel"
) {
    fun generatePatch(proxyServerId: Uuid): String? {
        val serverRow = serverRepository.findById(proxyServerId)
            ?: throw NotFoundException("Server not found")
        if (!serverRow.serverType.isProxy) {
            throw ConflictException("Server is not a proxy type")
        }
        if (serverRow.configMode == "MANUAL") return null

        val backends = proxyBackendRepository.listProxyBackends(proxyServerId)
            .sortedBy { it.order }
            .map { it to (serverRepository.findById(it.backendServerId)?.serverType ?: ServerType.VANILLA) }

        val ops = mutableListOf<JsonObject>()

        ops.add(serversOp(serverRow.serverType, backends))
        if (serverRow.serverType != ServerType.VELOCITY && backends.isNotEmpty()) {
            ops.add(prioritiesOp(backends.map { it.first }))
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

        val file = if (serverRow.serverType == ServerType.VELOCITY) VELOCITY_FILE else BUNGEE_FILE
        val patchSet = JsonObject(
            mapOf(
                "patches" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "file" to JsonPrimitive(file),
                                "ops" to JsonArray(ops)
                            )
                        )
                    )
                )
            )
        )
        return Json.encodeToString(patchSet)
    }

    private fun address(backendServerId: Uuid, backendServerType: ServerType): String = "$containerNamePrefix-$backendServerId:${images.internalListenPort(backendServerType)}"

    private fun serversOp(serverType: ServerType, backends: List<Pair<ProxyBackendRow, ServerType>>): JsonObject {
        val servers = buildMap<String, JsonElement> {
            backends.forEach { (b, backendType) ->
                put(
                    b.backendName,
                    if (serverType == ServerType.VELOCITY) {
                        JsonPrimitive(address(b.backendServerId, backendType))
                    } else {
                        JsonObject(
                            mapOf(
                                "address" to JsonPrimitive(address(b.backendServerId, backendType)),
                                "restricted" to JsonPrimitive(false)
                            )
                        )
                    }
                )
            }
            if (serverType == ServerType.VELOCITY) {
                put("try", JsonArray(backends.map { JsonPrimitive(it.first.backendName) }))
            }
        }
        return patchOp("$.servers", JsonObject(servers))
    }

    private fun prioritiesOp(backends: List<ProxyBackendRow>): JsonObject = patchOp("$.listeners[0].priorities", JsonArray(backends.map { JsonPrimitive(it.backendName) }))

    private fun motdOp(serverType: ServerType, motd: String): JsonObject {
        val path = if (serverType == ServerType.VELOCITY) "$.motd" else "$.listeners[0].motd"
        return patchOp(path, JsonPrimitive(motd))
    }

    private fun maxPlayersOp(serverType: ServerType, maxPlayers: Int): JsonObject {
        val (path, valueType) = if (serverType == ServerType.VELOCITY) {
            "\$['show-max-players']" to "int"
        } else {
            "$.player_limit" to "int"
        }
        return patchOpWithType(path, JsonPrimitive(maxPlayers), valueType)
    }

    private fun forwardingModeOp(serverType: ServerType, mode: String): JsonObject {
        if (serverType == ServerType.VELOCITY) {
            return patchOp("\$['player-info-forwarding-mode']", JsonPrimitive(mode.lowercase()))
        }
        val ipForward = mode == "LEGACY"
        return patchOpWithType("$.ip_forward", JsonPrimitive(ipForward), "bool")
    }

    private fun patchOp(path: String, value: JsonElement): JsonObject = JsonObject(
        mapOf("\$set" to JsonObject(mapOf("path" to JsonPrimitive(path), "value" to value)))
    )

    private fun patchOpWithType(path: String, value: JsonElement, valueType: String): JsonObject = JsonObject(
        mapOf(
            "\$set" to JsonObject(
                mapOf(
                    "path" to JsonPrimitive(path),
                    "value" to value,
                    "value-type" to JsonPrimitive(valueType)
                )
            )
        )
    )
}
