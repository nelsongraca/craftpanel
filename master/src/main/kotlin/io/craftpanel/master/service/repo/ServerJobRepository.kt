package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

data class ServerJobRow(val id: Uuid, val serverId: Uuid, val type: String, val cronExpression: String, val payload: String? = null, val enabled: Boolean = true, val lastFiredAt: String? = null)

interface ServerJobRepository {

    fun listEnabledServerJobs(): List<ServerJobRow>

    fun listByServer(serverId: Uuid): List<ServerJobRow>

    fun findById(id: Uuid): ServerJobRow?

    fun create(serverId: Uuid, type: String, cronExpression: String, payload: String?, enabled: Boolean): ServerJobRow

    fun update(id: Uuid, cronExpression: String?, payload: String?, enabled: Boolean?)

    /** Returns true when a row was deleted. */
    fun delete(id: Uuid): Boolean
}
