package io.craftpanel.master.service.repo.impl

import io.craftpanel.master.database.schema.SystemSettings
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
import io.craftpanel.master.util.toUtcString
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SettingsRepositoryImpl : SettingsRepository {

    override fun getAll(): List<SettingsEntry> = transaction {
        SystemSettings.selectAll()
            .map {
                SettingsEntry(
                    key = it[SystemSettings.key],
                    value = it[SystemSettings.value],
                    updatedAt = it[SystemSettings.updatedAt].toUtcString(),
                    updatedBy = it[SystemSettings.updatedBy]
                )
            }
    }

    override fun upsert(key: String, value: String, updatedAt: kotlin.time.Instant?, updatedBy: Uuid?) {
        transaction {
            SystemSettings.upsert {
                it[SystemSettings.key] = key
                it[SystemSettings.value] = value
                it[SystemSettings.updatedBy] = updatedBy
                it[SystemSettings.updatedAt] = (updatedAt ?: Instant.fromEpochMilliseconds(System.currentTimeMillis())).toLocalDateTime(TimeZone.UTC)
            }
        }
    }
}
