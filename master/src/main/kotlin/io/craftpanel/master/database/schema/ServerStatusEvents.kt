package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Append-only log of agent-reported server status *transitions*. A row is written only when the
 * reported status differs from the last persisted one (see `NodeObserver.persistServerStatus`), so
 * this table stays small relative to the metrics tables. Pruned by `RetentionJanitor` using the
 * `metric_retention_days` setting.
 */
object ServerStatusEvents : UuidTable("server_status_events") {

    val serverId = reference("server_id", Servers, onDelete = ReferenceOption.CASCADE)
    val status = varchar("status", Servers.STATUS_MAX_LENGTH)
    val recordedAt = datetime("recorded_at")

    init {
        index(false, serverId, recordedAt)
    }
}
