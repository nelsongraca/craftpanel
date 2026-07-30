package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

class FakeSettingsRepository : SettingsRepository {

    private val settings = mutableMapOf<String, String>()

    override fun getAll(): List<SettingsEntry> = settings.map { (k, v) -> SettingsEntry(k, v, "2025-01-01T00:00:00Z", null) }

    fun addSetting(key: String, value: String) {
        settings[key] = value
    }
}