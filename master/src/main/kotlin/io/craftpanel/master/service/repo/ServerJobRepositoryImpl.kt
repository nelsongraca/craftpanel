package io.craftpanel.master.service.repo

import io.craftpanel.master.database.schema.ServerJobs
import kotlinx.datetime.*
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

class ServerJobRepositoryImpl : ServerJobRepository {

    override fun listEnabledServerJobs(): List<ServerJobRow> = transaction {
        ServerJobs.selectAll()
            .where { ServerJobs.enabled eq true }
            .map { it.toServerJobRow() }
    }

    override fun updateServerJobLastFired(jobId: Uuid, lastFired: Instant) {
        transaction {
            ServerJobs.update({ ServerJobs.id eq jobId }) {
                it[ServerJobs.lastFiredAt] = lastFired.toLocalDateTime(TimeZone.UTC)
            }
        }
    }
}

private fun ResultRow.toServerJobRow() = ServerJobRow(
    id = this[ServerJobs.id],
    serverId = this[ServerJobs.serverId],
    type = this[ServerJobs.type],
    cronExpression = this[ServerJobs.cronExpression],
    lastFiredAt = this[ServerJobs.lastFiredAt]?.toString()
)
