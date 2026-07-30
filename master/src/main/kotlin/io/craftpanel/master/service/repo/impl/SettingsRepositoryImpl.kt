package io.craftpanel.master.service.repo.impl

import io.craftpanel.master.database.schema.SystemSettings
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class SettingsRepositoryImpl : SettingsRepository {

    override fun getAll(): List<SettingsEntry> = transaction {
        SystemSettings.selectAll()
            .map {
                SettingsEntry(
                    key = it[SystemSettings.key],
                    value = it[SystemSettings.value],
                    updatedAt = it[SystemSettings.updatedAt].toUtcString(),
                    updatedBy = it[SystemSettings.updatedBy]?.value
                )
            }
    }
}