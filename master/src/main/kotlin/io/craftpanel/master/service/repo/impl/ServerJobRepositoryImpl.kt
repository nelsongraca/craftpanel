package io.craftpanel.master.service.repo.impl

import io.craftpanel.master.database.schema.ServerJobs
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class ServerJobRepositoryImpl : ServerJobRepository {

    override fun listEnabledServerJobs(): List<ServerJobRow> = transaction {
        ServerJobs.selectAll()
            .where { ServerJobs.enabled eq true }
            .map { it.toServerJobRow() }
    }
}

private fun ResultRow.toServerJobRow() = ServerJobRow(
    id = this[ServerJobs.id].value,
    serverId = this[ServerJobs.serverId].value,
    type = this[ServerJobs.type],
    cronExpression = this[ServerJobs.cronExpression],
    lastFiredAt = this[ServerJobs.lastFiredAt]?.toUtcString()
)