package io.craftpanel.master.service

import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.service.repo.ServerRepository
import io.craftpanel.master.service.repo.ServerRow
import kotlin.uuid.Uuid

class ServerLifecycleService(
    private val lifecycle: ContainerLifecycle,
    private val serverRepository: ServerRepository,
    private val serverExposure: ServerExposure,
    private val proxyConfigPatchService: ProxyConfigPatchService,
    private val writeFile: suspend (Uuid, String, ByteArray) -> Unit
) {

    suspend fun startServer(id: Uuid) {
        val serverRow = serverRepository.findById(id) ?: throw NotFoundException("Server not found")
        val status = ServerStatus.fromDb(serverRow.status)
        if (status == ServerStatus.HEALTHY || status == ServerStatus.STARTING) {
            throw ConflictException("Server is already running")
        }
        val publicHostname = serverExposure.mcRouterLabel(serverRow)
        // Write the proxy patch before flipping status to STARTING: a failure here must
        // surface loudly and leave the server's prior status untouched, not strand it at
        // STARTING with no process actually starting.
        writeProxyPatch(serverRow)
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

    fun forceStopServer(id: Uuid) {
        val serverRow = serverRepository.findById(id) ?: throw NotFoundException("Server not found")
        if (serverRow.status == "STOPPED") throw ConflictException("Server is already stopped")
        val nodeId = serverRow.nodeId.toString()
        serverRepository.updateStatus(id, "STOPPING", null)
        lifecycle.sendStop(serverRow, nodeId, force = true)
    }

    suspend fun restartServer(id: Uuid) {
        val serverRow = serverRepository.findById(id) ?: throw NotFoundException("Server not found")
        if (ServerStatus.fromDb(serverRow.status).isStopped) throw ConflictException("Server is not running")
        val nodeId = serverRow.nodeId.toString()
        writeProxyPatch(serverRow)
        serverRepository.updateStatus(id, "STARTING", null)
        if (serverRow.needsRecreate) {
            lifecycle.sendStart(
                serverRow,
                needsRecreate = true,
                publicHostname = serverExposure.mcRouterLabel(serverRow)
            )
        } else {
            lifecycle.sendStop(serverRow, nodeId)
            lifecycle.sendStart(
                serverRow,
                needsRecreate = false,
                publicHostname = serverExposure.mcRouterLabel(serverRow)
            )
        }
    }

    private suspend fun writeProxyPatch(server: ServerRow) {
        if (!server.serverType.isProxy) return
        val patch = proxyConfigPatchService.generatePatch(server.id)
        // writeFile's path is resolved relative to the server's data root (bind-mounted to
        // container /server), which IS PATCH_DEFINITIONS' /server — so no dataContainerPath prefix here.
        writeFile(server.id, "craftpanel-patch.json", patch.toByteArray())
    }
}
