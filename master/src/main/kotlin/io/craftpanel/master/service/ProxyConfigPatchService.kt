package io.craftpanel.master.service

import io.craftpanel.master.config.ImagesConfig
import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.service.repo.ProxyBackendRepository
import io.craftpanel.master.service.repo.ProxyBackendRow
import io.craftpanel.master.service.repo.ServerRepository
import io.craftpanel.master.service.repo.ServerRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid

private const val VELOCITY_FILE = "/server/velocity.toml"
private const val BUNGEE_FILE = "/server/config.yml"

private data class ProxyDialect(
    val motdPath: String,
    val maxPlayersPath: String,
    val maxPlayersValueType: String,
    val forwardingModePath: String,
    val forwardingModeValueType: String?,
    val isVelocity: Boolean
)

private val VELOCITY_DIALECT = ProxyDialect(
    motdPath = "$.motd",
    maxPlayersPath = "\$['show-max-players']",
    maxPlayersValueType = "int",
    forwardingModePath = "\$['player-info-forwarding-mode']",
    forwardingModeValueType = null,
    isVelocity = true
)

private val BUNGEE_DIALECT = ProxyDialect(
    motdPath = "$.listeners[0].motd",
    maxPlayersPath = "$.player_limit",
    maxPlayersValueType = "int",
    forwardingModePath = "$.ip_forward",
    forwardingModeValueType = "bool",
    isVelocity = false
)

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

        val dialect = if (serverRow.serverType == ServerType.VELOCITY) VELOCITY_DIALECT else BUNGEE_DIALECT

        val backends = proxyBackendRepository.listProxyBackends(proxyServerId)
            .sortedBy { it.order }
            .map { it to serverRepository.findById(it.backendServerId) }

        val ops = mutableListOf<JsonObject>()

        ops.add(serversOp(dialect, backends))
        if (serverRow.serverType != ServerType.VELOCITY && backends.isNotEmpty()) {
            ops.add(prioritiesOp(backends.map { it.first }))
        }
        if (serverRow.proxyMotd != null) {
            ops.add(motdOp(dialect, serverRow.proxyMotd))
        }
        if (serverRow.proxyMaxPlayers != null) {
            ops.add(maxPlayersOp(dialect, serverRow.proxyMaxPlayers))
        }
        if (serverRow.proxyForwardingMode != null) {
            ops.add(forwardingModeOp(dialect, serverRow.proxyForwardingMode))
        }

        val file = if (dialect.isVelocity) VELOCITY_FILE else BUNGEE_FILE
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

    private fun address(backendServerRow: ServerRow?): String {
        val fallbackType = backendServerRow?.serverType ?: ServerType.VANILLA
        val port = backendServerRow?.containerListenPort ?: images.internalListenPort(fallbackType)
        return "$containerNamePrefix-${backendServerRow?.id ?: "unknown"}:$port"
    }

    private fun serversOp(dialect: ProxyDialect, backends: List<Pair<ProxyBackendRow, ServerRow?>>): JsonObject {
        val servers = buildMap<String, JsonElement> {
            backends.forEach { (b, backendRow) ->
                put(
                    b.backendName,
                    if (dialect.isVelocity) {
                        JsonPrimitive(address(backendRow))
                    } else {
                        JsonObject(
                            mapOf(
                                "address" to JsonPrimitive(address(backendRow)),
                                "restricted" to JsonPrimitive(false)
                            )
                        )
                    }
                )
            }
            if (dialect.isVelocity) {
                put("try", JsonArray(backends.map { JsonPrimitive(it.first.backendName) }))
            }
        }
        return patchOp("$.servers", JsonObject(servers))
    }

    private fun prioritiesOp(backends: List<ProxyBackendRow>): JsonObject = patchOp("$.listeners[0].priorities", JsonArray(backends.map { JsonPrimitive(it.backendName) }))

    private fun motdOp(dialect: ProxyDialect, motd: String): JsonObject = patchOp(dialect.motdPath, JsonPrimitive(motd))

    private fun maxPlayersOp(dialect: ProxyDialect, maxPlayers: Int): JsonObject = patchOpWithType(dialect.maxPlayersPath, JsonPrimitive(maxPlayers), dialect.maxPlayersValueType)

    private fun forwardingModeOp(dialect: ProxyDialect, mode: String): JsonObject {
        if (dialect.isVelocity) {
            return patchOp(dialect.forwardingModePath, JsonPrimitive(mode.lowercase()))
        }
        val ipForward = mode == "LEGACY"
        return patchOpWithType(dialect.forwardingModePath, JsonPrimitive(ipForward), dialect.forwardingModeValueType!!)
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
