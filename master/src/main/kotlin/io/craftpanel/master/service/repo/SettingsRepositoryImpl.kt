package io.craftpanel.master.service.repo

import io.craftpanel.master.database.schema.ServerJobs
import io.craftpanel.master.database.schema.SystemSettings
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.uuid.Uuid

class SettingsRepositoryImpl : SettingsRepository {

    override fun getAll(): List<SettingsEntry> = transaction {
        SystemSettings.selectAll()
            .map {
                SettingsEntry(
                    key = it[SystemSettings.key],
                    value = it[SystemSettings.value],
                    updatedAt = it[SystemSettings.updatedAt].toString(),
                    updatedBy = it[SystemSettings.updatedBy],
                )
            }
    }

    override fun upsert(key: String, value: String, updatedAt: Instant?, updatedBy: Uuid?) {
        transaction {
            SystemSettings.upsert {
                it[SystemSettings.key] = key
                it[SystemSettings.value] = value
                it[SystemSettings.updatedBy] = updatedBy
                it[SystemSettings.updatedAt] = (updatedAt ?: Instant.fromEpochMilliseconds(System.currentTimeMillis())).toLocalDateTime(TimeZone.UTC)
            }
        }
    }

    override fun findJobsByType(type: String): List<ServerJobRow> = transaction {
        ServerJobs.selectAll()
            .where { ServerJobs.type eq type }
            .map { it.toServerJobRow() }
    }

    override fun updateJobLastFired(jobId: Uuid, lastFiredAt: Instant) {
        transaction {
            ServerJobs.update({ ServerJobs.id eq jobId }) {
                it[ServerJobs.lastFiredAt] = lastFiredAt.toLocalDateTime(TimeZone.UTC)
            }
        }
    }

    override fun findEnabledJobs(): List<ServerJobRow> = transaction {
        ServerJobs.selectAll()
            .where { ServerJobs.enabled eq true }
            .map { it.toServerJobRow() }
    }
}

private fun org.jetbrains.exposed.v1.core.ResultRow.toServerJobRow() = ServerJobRow(
    id = this[ServerJobs.id],
    serverId = this[ServerJobs.serverId],
    type = this[ServerJobs.type],
    cronExpression = this[ServerJobs.cronExpression],
    enabled = this[ServerJobs.enabled],
    lastFiredAt = this[ServerJobs.lastFiredAt]?.toString(),
)
