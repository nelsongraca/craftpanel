package io.craftpanel.master.service

import io.craftpanel.master.config.ImagesConfig
import io.craftpanel.master.service.repo.SettingsRepository

/**
 * The single cached snapshot of system settings. Reads are served from memory so the settings are
 * parsed once instead of on every call; [invalidate] (called by [SystemService] after a write) drops
 * the cache so a live change takes effect without a restart.
 *
 * The raw `key -> value` map is cached alongside the parsed [Settings] so the one secret that must
 * not appear in the serialised snapshot (`cf_api_token`) can be read without a per-use DB hit.
 */
class SettingsProvider(private val settingsRepository: SettingsRepository) {

    @Volatile
    private var cached: Settings? = null

    @Volatile
    private var cachedRaw: Map<String, String>? = null

    fun current(): Settings = cached ?: synchronized(this) {
        cached ?: load().first
    }

    /** Raw stored value for [key], or null when the row is absent. */
    fun settingValue(key: String): String? {
        val raw = cachedRaw ?: synchronized(this) { cachedRaw ?: load().second }
        return raw[key]
    }

    /** The stored (still encrypted) `cf_api_token`, or null when unset/blank. */
    fun encryptedCfApiToken(): String? = settingValue("cf_api_token")?.takeIf { it.isNotBlank() }

    /** The live image config, derived from the current settings snapshot. */
    fun images(): ImagesConfig = current().let { ImagesConfig(it.imageMinecraft, it.imageProxy) }

    fun invalidate() {
        cached = null
        cachedRaw = null
    }

    private fun load(): Pair<Settings, Map<String, String>> = synchronized(this) {
        val rows = settingsRepository.getAll()
        val settings = Settings.from(rows)
        val raw = rows.associate { it.key to it.value }
        cached = settings
        cachedRaw = raw
        settings to raw
    }
}
