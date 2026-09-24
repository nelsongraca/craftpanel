package io.craftpanel.master.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one builder for itzg `PATCH_DEFINITIONS` JSON-patch documents, shared by the proxy-settings
 * patch ([ProxyConfigPatchService]) and the backend forwarding patch ([BackendForwardingRenderer]).
 */
internal object ProxyPatch {

    /** `{"patches":[{"file": <file>, "file-format"?: <format>, "ops": [...]}]}`. */
    fun patchSet(file: String, ops: List<JsonObject>, fileFormat: String? = null): String {
        val entry = buildMap<String, JsonElement> {
            put("file", JsonPrimitive(file))
            if (fileFormat != null) put("file-format", JsonPrimitive(fileFormat))
            put("ops", JsonArray(ops))
        }
        return Json.encodeToString(JsonObject(mapOf("patches" to JsonArray(listOf(JsonObject(entry))))))
    }

    /**
     * A `$set` op — overwrites an existing field. mc-image-helper (via Jayway JsonPath) throws
     * `PathNotFoundException` when the target key/array index is absent, so use [put] for keys that
     * may not exist in the stock config (a missing key would otherwise fail the whole PatchSet).
     * [valueType] adds `value-type` when non-null.
     */
    fun set(path: String, value: JsonElement, valueType: String? = null): JsonObject {
        val fields = buildMap<String, JsonElement> {
            put("path", JsonPrimitive(path))
            put("value", value)
            if (valueType != null) put("value-type", JsonPrimitive(valueType))
        }
        return JsonObject(mapOf("\$set" to JsonObject(fields)))
    }

    /** A `$put` op — adds or updates [key] under the object at [path]. Unlike [set], it creates a missing key. */
    fun put(path: String, key: String, value: JsonElement, valueType: String? = null): JsonObject {
        val fields = buildMap<String, JsonElement> {
            put("path", JsonPrimitive(path))
            put("key", JsonPrimitive(key))
            put("value", value)
            if (valueType != null) put("value-type", JsonPrimitive(valueType))
        }
        return JsonObject(mapOf("\$put" to JsonObject(fields)))
    }
}
