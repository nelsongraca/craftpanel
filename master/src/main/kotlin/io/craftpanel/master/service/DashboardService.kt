package io.craftpanel.master.service

import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.routes.DashboardEventFilter
import io.craftpanel.master.routes.WsEnvelope
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlin.uuid.Uuid

class DashboardService(
    private val agentGateway: AgentGateway,
    private val serverRepository: ServerRepository,
    private val nodeRepository: NodeRepository,
    private val containerMetricsRepository: ContainerMetricsRepository,
    private val permissionResolver: PermissionResolver
) {

    fun getSnapshot(userId: Uuid): WsEnvelope {
        val filter = buildFilter(userId)
        val serverRows = serverRepository.listAll()
        val latestMetrics = containerMetricsRepository.getLatestContainerMetricsForServers(serverRows.map { it.id })
        val nodeRows = nodeRepository.listAll()
        return filter.snapshot(serverRows, latestMetrics, nodeRows)
    }

    fun filteredEvents(userId: Uuid): Flow<WsEnvelope> {
        val filter = buildFilter(userId)
        return agentGateway.agentEvents.mapNotNull { filter.toEnvelope(it) }
    }

    private fun buildFilter(userId: Uuid) = DashboardEventFilter(
        hasNodes = { permissionResolver.hasPermission(userId, Permission.SYSTEM_NODES) },
        canViewServer = { serverId, networkId ->
            permissionResolver.hasPermission(userId, Permission.SERVER_VIEW, serverId, networkId)
        },
        serverNetworkId = { serverId ->
            val kId = runCatching { Uuid.parse(serverId) }.getOrNull()
            kId?.let { serverRepository.findById(it)?.networkId }
        }
    )
}
