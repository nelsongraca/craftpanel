package io.craftpanel.master.service

import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.service.repo.NetworkRepository
import io.craftpanel.master.service.repo.ServerRepository
import io.craftpanel.master.service.repo.SettingsRepository
import kotlin.uuid.Uuid

class ServerLifecycleService(
    private val lifecycle: ContainerLifecycle,
    private val serverRepository: ServerRepository,
    private val networkRepository: NetworkRepository,
    private val settingsRepository: SettingsRepository,
) {

    fun startServer(id: Uuid) {
        val serverRow = serverRepository.findById(id) ?: throw NotFoundException("Server not found")
        val status = ServerStatus.fromDb(serverRow.status)
        if (status == ServerStatus.HEALTHY || status == ServerStatus.STARTING)
            throw ConflictException("Server is already running")
        val publicHostname = buildMcRouterLabel(serverRow, networkRepository, settingsRepository)
        serverRepository.updateStatus(id, "STARTING", null)
        lifecycle.sendStart(serverRow, needsRecreate = serverRow.needsRecreate, publicHostname = publicHostname)
    }

    fun stopServer(id: Uuid) {
        val serverRow = serverRepository.findById(id) ?: throw NotFoundException("Server not found")
        if (serverRow.status == "STOPPED") throw ConflictException("Server is already stopped")
        val nodeId = serverRow.nodeId.toString()
        serverRepository.updateStatus(id, "STOPPING", null)
        lifecycle.sendStop(serverRow, nodeId)
    }

    fun restartServer(id: Uuid) {
        val serverRow = serverRepository.findById(id) ?: throw NotFoundException("Server not found")
        if (ServerStatus.fromDb(serverRow.status).isStopped) throw ConflictException("Server is not running")
        val nodeId = serverRow.nodeId.toString()
        serverRepository.updateStatus(id, "STARTING", null)
        if (serverRow.needsRecreate) {
            lifecycle.sendStart(
                serverRow,
                needsRecreate = true,
                publicHostname = buildMcRouterLabel(serverRow, networkRepository, settingsRepository),
            )
        }
        else {
            lifecycle.sendStop(serverRow, nodeId)
            lifecycle.sendStart(
                serverRow,
                needsRecreate = false,
                publicHostname = buildMcRouterLabel(serverRow, networkRepository, settingsRepository),
            )
        }
    }
}
