package io.craftpanel.master.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object BackendForwardingRenderer {

    fun render(file: String, secret: String): String {
        val patchSet = JsonObject(
            mapOf(
                "patches" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "file" to JsonPrimitive(file),
                                "file-format" to JsonPrimitive("yaml"),
                                "ops" to JsonArray(
                                    when {
                                        file.endsWith("paper-global.yml") ->
                                            modernOps(secret)

                                        file.endsWith("spigot.yml") ->
                                            legacyOps()

                                        else -> throw IllegalArgumentException("Unknown file: $file")
                                    }
                                )
                            )
                        )
                    )
                )
            )
        )
        return Json.encodeToString(patchSet)
    }

    private fun modernOps(secret: String): List<JsonObject> = listOf(
        patchOp("\$.proxies.velocity.enabled", JsonPrimitive(true), "bool"),
        patchOp("\$.proxies.velocity['online-mode']", JsonPrimitive(true), "bool"),
        patchOp("\$.proxies.velocity.secret", JsonPrimitive(secret))
    )

    private fun legacyOps(): List<JsonObject> = listOf(
        patchOp("\$.settings.bungeecord", JsonPrimitive(true), "bool")
    )

    private fun patchOp(path: String, value: JsonPrimitive, valueType: String? = null): JsonObject {
        val fields = mutableMapOf<String, JsonPrimitive>(
            "path" to JsonPrimitive(path),
            "value" to value
        )
        if (valueType != null) {
            fields["value-type"] = JsonPrimitive(valueType)
        }
        return JsonObject(mapOf("\$set" to JsonObject(fields)))
    }
}
