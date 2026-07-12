package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

class FakeSettingsRepository : SettingsRepository {

    private val settings = mutableMapOf<String, String>()
    private var updatedByValue: Uuid? = null

    override fun getAll(): List<SettingsEntry> = settings.map { (k, v) -> SettingsEntry(k, v, "2025-01-01T00:00:00Z", updatedByValue) }
    override fun upsert(key: String, value: String, updatedAt: kotlin.time.Instant?, updatedBy: Uuid?) {
        settings[key] = value
        if (updatedBy != null) updatedByValue = updatedBy
    }
}
