package io.craftpanel.master.service.repo.impl

import io.craftpanel.master.database.schema.ServerStatusEvents
import io.craftpanel.master.service.repo.ServerStatusEventRow
import io.craftpanel.master.service.repo.ServerStatusHistoryRepository
import io.craftpanel.master.util.toUtcString
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ServerStatusHistoryRepositoryImpl : ServerStatusHistoryRepository {

    override fun listRecent(serverId: Uuid, limit: Int): List<ServerStatusEventRow> = transaction {
        ServerStatusEvents.selectAll()
            .where { ServerStatusEvents.serverId eq serverId }
            .orderBy(ServerStatusEvents.recordedAt to SortOrder.DESC)
            .limit(limit)
            .map {
                ServerStatusEventRow(
                    id = it[ServerStatusEvents.id].value,
                    serverId = it[ServerStatusEvents.serverId].value,
                    status = it[ServerStatusEvents.status],
                    recordedAt = it[ServerStatusEvents.recordedAt].toUtcString()
                )
            }
    }

    override fun deleteOlderThan(cutoff: Instant): Int = transaction {
        val cutoffLdt = cutoff.toLocalDateTime(TimeZone.UTC)
        ServerStatusEvents.deleteWhere { ServerStatusEvents.recordedAt less cutoffLdt }
    }
}
