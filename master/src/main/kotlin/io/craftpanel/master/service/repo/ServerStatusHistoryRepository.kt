package io.craftpanel.master.service.repo

import kotlin.time.Instant
import kotlin.uuid.Uuid

data class ServerStatusEventRow(val id: Uuid, val serverId: Uuid, val status: String, val recordedAt: String)

interface ServerStatusHistoryRepository {

    /** Most recent transitions for a server, newest first. */
    fun listRecent(serverId: Uuid, limit: Int): List<ServerStatusEventRow>

    /** Deletes transitions recorded strictly before [cutoff]. Returns the number of rows removed. */
    fun deleteOlderThan(cutoff: Instant): Int
}
