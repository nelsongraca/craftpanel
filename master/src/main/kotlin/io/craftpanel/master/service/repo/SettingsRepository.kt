package io.craftpanel.master.service.repo

import kotlinx.datetime.Instant
import kotlin.uuid.Uuid

data class SettingsEntry(val key: String, val value: String, val updatedAt: String, val updatedBy: Uuid?)

interface SettingsRepository {

    fun getAll(): List<SettingsEntry>
    fun upsert(key: String, value: String, updatedAt: Instant?, updatedBy: Uuid?)
}
