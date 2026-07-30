package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

data class SettingsEntry(val key: String, val value: String, val updatedAt: String, val updatedBy: Uuid?)

interface SettingsRepository {

    fun getAll(): List<SettingsEntry>
}
