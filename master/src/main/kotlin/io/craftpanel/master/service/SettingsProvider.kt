package io.craftpanel.master.service

import io.craftpanel.master.config.ImagesConfig
import io.craftpanel.master.service.repo.SettingsRepository

/**
 * The single cached snapshot of system settings. Reads are served from memory so the settings are
 * parsed once instead of on every call; [invalidate] (called by [SystemService] after a write) drops
 * the cache so a live change takes effect without a restart.
 */
class SettingsProvider(private val settingsRepository: SettingsRepository) {

    @Volatile
    private var cached: Settings? = null

    fun current(): Settings = cached ?: synchronized(this) {
        cached ?: Settings.from(settingsRepository.getAll()).also { cached = it }
    }

    /** The live image config, derived from the current settings snapshot. */
    fun images(): ImagesConfig = current().let { ImagesConfig(it.imageMinecraft, it.imageProxy) }

    fun invalidate() {
        cached = null
    }
}
