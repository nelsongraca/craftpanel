package io.craftpanel.master.service

import io.craftpanel.master.service.repo.ServerRepository
import io.craftpanel.master.service.repo.ServerView
import kotlin.uuid.Uuid

/**
 * Fetch a server and require it to be a proxy type — the single guard behind every proxy endpoint
 * (settings, backends, patch generation). Throws [NotFoundException] when the server is missing and
 * [ConflictException] when it is not a proxy.
 */
internal fun ServerRepository.requireProxy(serverId: Uuid): ServerView {
    val row = findById(serverId) ?: throw NotFoundException("Server not found")
    if (!row.serverType.isProxy) throw ConflictException("Server is not a proxy type")
    return row
}
