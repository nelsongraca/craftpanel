package io.craftpanel.master.service.repo

import io.craftpanel.master.util.parseUtcInstant
import kotlin.uuid.Uuid

class FakeServerStatusHistoryRepository(private val state: FakeRepositories) : ServerStatusHistoryRepository {

    override fun listRecent(serverId: Uuid, limit: Int): List<ServerStatusEventRow> = state.statusHistory
        .filter { it.serverId == serverId }
        .sortedByDescending { it.recordedAt }
        .take(limit)

    override fun deleteOlderThan(cutoff: kotlin.time.Instant): Int {
        val before = state.statusHistory.size
        state.statusHistory.removeAll { parseUtcInstant(it.recordedAt)?.let { t -> t < cutoff } ?: false }
        return before - state.statusHistory.size
    }
}
