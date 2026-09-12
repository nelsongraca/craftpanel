package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

data class ServerExtraPortRow(
    val id: Uuid,
    val serverId: Uuid,
    val nodeId: Uuid,
    val name: String,
    val containerPort: Int,
    val hostPort: Int,
    val protocol: String,
    val createdAt: String,
    val updatedAt: String
)

interface ServerExtraPortRepository {
    fun findByServerId(serverId: Uuid): List<ServerExtraPortRow>
    fun createExtraPort(serverId: Uuid, nodeId: Uuid, name: String, containerPort: Int, hostPort: Int?, protocol: String): ServerExtraPortRow
    fun deleteExtraPort(portId: Uuid): Boolean
}
