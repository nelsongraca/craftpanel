package io.craftpanel.master.service

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object BackendForwardingRenderer {

    fun render(file: String, secret: String): String {
        val ops = when {
            file.endsWith("paper-global.yml") -> modernOps(secret)

            file.endsWith("spigot.yml") -> legacyOps()

            else -> throw IllegalArgumentException("Unknown file: $file")
        }
        return ProxyPatch.patchSet(file, ops, fileFormat = "yaml")
    }

    private fun modernOps(secret: String): List<JsonObject> = listOf(
        ProxyPatch.set("\$.proxies.velocity.enabled", JsonPrimitive(true), "bool"),
        ProxyPatch.set("\$.proxies.velocity['online-mode']", JsonPrimitive(true), "bool"),
        ProxyPatch.set("\$.proxies.velocity.secret", JsonPrimitive(secret))
    )

    private fun legacyOps(): List<JsonObject> = listOf(
        ProxyPatch.set("\$.settings.bungeecord", JsonPrimitive(true), "bool")
    )
}
