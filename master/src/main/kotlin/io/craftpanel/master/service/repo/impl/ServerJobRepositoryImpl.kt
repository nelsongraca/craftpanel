package io.craftpanel.master.service.repo.impl

import io.craftpanel.master.database.schema.ServerJobs
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
import io.craftpanel.master.util.toUtcString
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

class ServerJobRepositoryImpl : ServerJobRepository {

    override fun listEnabledServerJobs(): List<ServerJobRow> = transaction {
        ServerJobs.selectAll()
            .where { ServerJobs.enabled eq true }
            .map { it.toServerJobRow() }
    }

    override fun listByServer(serverId: Uuid): List<ServerJobRow> = transaction {
        ServerJobs.selectAll()
            .where { ServerJobs.serverId eq serverId }
            .map { it.toServerJobRow() }
    }

    override fun findById(id: Uuid): ServerJobRow? = transaction {
        ServerJobs.selectAll()
            .where { ServerJobs.id eq EntityID(id, ServerJobs) }
            .firstOrNull()
            ?.toServerJobRow()
    }

    override fun create(serverId: Uuid, type: String, cronExpression: String, payload: String?, enabled: Boolean): ServerJobRow = transaction {
        val id = ServerJobs.insert {
            it[ServerJobs.serverId] = EntityID(serverId, Servers)
            it[ServerJobs.type] = type
            it[ServerJobs.cronExpression] = cronExpression
            it[ServerJobs.payload] = payload
            it[ServerJobs.enabled] = enabled
        }[ServerJobs.id]
        ServerJobs.selectAll()
            .where { ServerJobs.id eq id }
            .first()
            .toServerJobRow()
    }

    override fun update(id: Uuid, cronExpression: String?, payload: String?, enabled: Boolean?) {
        transaction {
            ServerJobs.update({ ServerJobs.id eq EntityID(id, ServerJobs) }) {
                if (cronExpression != null) it[ServerJobs.cronExpression] = cronExpression
                if (payload != null) it[ServerJobs.payload] = payload
                if (enabled != null) it[ServerJobs.enabled] = enabled
                it[ServerJobs.updatedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
            }
        }
    }

    override fun delete(id: Uuid): Boolean = transaction {
        ServerJobs.deleteWhere { ServerJobs.id eq EntityID(id, ServerJobs) } > 0
    }
}

private fun ResultRow.toServerJobRow() = ServerJobRow(
    id = this[ServerJobs.id].value,
    serverId = this[ServerJobs.serverId].value,
    type = this[ServerJobs.type],
    cronExpression = this[ServerJobs.cronExpression],
    payload = this[ServerJobs.payload],
    enabled = this[ServerJobs.enabled],
    lastFiredAt = this[ServerJobs.lastFiredAt]?.toUtcString()
)
