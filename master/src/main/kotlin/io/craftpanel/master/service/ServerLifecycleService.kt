package io.craftpanel.master.service

import io.craftpanel.master.domain.DesiredStatus
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.service.repo.ServerRepository
import io.craftpanel.master.service.repo.ServerRow
import io.craftpanel.master.service.repo.disabledReason
import io.craftpanel.master.service.repo.isDisabled
import kotlin.uuid.Uuid

/**
 * User-facing server lifecycle. In the desired-state model these operations only record master's
 * intent (`desired_status`) and push a `ServerDesiredState` envelope — the agent converges and owns
 * the actual container mechanics (including crash-restart). No transient status (STARTING/STOPPING)
 * is ever written to the DB; those are derived at read time by [io.craftpanel.master.domain.synthesizeStatus].
 */
class ServerLifecycleService(
    private val lifecycle: ContainerLifecycle,
    private val serverRepository: ServerRepository,
    private val serverExposure: ServerExposure,
    private val proxyConfigPatchService: ProxyConfigPatchService,
    private val writeFile: suspend (Uuid, String, ByteArray) -> Unit
) {

    suspend fun startServer(id: Uuid) {
        val serverRow = serverRepository.findById(id) ?: throw NotFoundException("Server not found")
        val desired = DesiredStatus.fromDb(serverRow.desiredStatus)
        val reported = ServerStatus.fromDb(serverRow.status)
        // Conflict on agent-reported state (not intent): a server the agent already reports as
        // running/starting cannot be started again. Intent RUNNING with a stopped/crashed report is
        // recovery, handled below via force_restart.
        val alreadyRunning = reported == ServerStatus.HEALTHY || reported == ServerStatus.STARTING
        if (alreadyRunning) throw ConflictException("Server is already running")
        if (serverRow.isDisabled()) throw ConflictException(serverRow.disabledReason())
        val publicHostname = serverExposure.mcRouterLabel(serverRow)
        // Write the proxy patch before pushing intent: a failure here must surface loudly and leave
        // the prior intent untouched, not strand the server at a running intent with no process starting.
        writeProxyPatch(serverRow)
        // Re-issuing a start while intent is already RUNNING means recovery from a failed/crash-looped
        // container — force a restart so the agent retries past its exhausted crash budget.
        val forceRestart = desired == DesiredStatus.RUNNING
        val previous = serverRow.desiredStatus
        serverRepository.updateDesiredStatus(id, DesiredStatus.RUNNING.toDb())
        if (!lifecycle.sendDesiredState(serverRow, DesiredStatus.RUNNING, forceRestart = forceRestart, publicHostname = publicHostname)) {
            serverRepository.updateDesiredStatus(id, previous)
            throw BadGatewayException("Agent not connected")
        }
    }

    fun stopServer(id: Uuid) = requestStop(id, force = false)

    fun forceStopServer(id: Uuid) = requestStop(id, force = true)

    suspend fun restartServer(id: Uuid) {
        val serverRow = serverRepository.findById(id) ?: throw NotFoundException("Server not found")
        if (ServerStatus.fromDb(serverRow.status).isStopped) throw ConflictException("Server is not running")
        if (serverRow.isDisabled()) throw ConflictException(serverRow.disabledReason())
        writeProxyPatch(serverRow)
        val previous = serverRow.desiredStatus
        serverRepository.updateDesiredStatus(id, DesiredStatus.RUNNING.toDb())
        if (!lifecycle.sendDesiredState(serverRow, DesiredStatus.RUNNING, forceRestart = true, publicHostname = serverExposure.mcRouterLabel(serverRow))) {
            serverRepository.updateDesiredStatus(id, previous)
            throw BadGatewayException("Agent not connected")
        }
    }

    private fun requestStop(id: Uuid, force: Boolean) {
        val serverRow = serverRepository.findById(id) ?: throw NotFoundException("Server not found")
        if (ServerStatus.fromDb(serverRow.status) == ServerStatus.STOPPED) {
            throw ConflictException("Server is already stopped")
        }
        val previous = serverRow.desiredStatus
        serverRepository.updateDesiredStatus(id, DesiredStatus.STOPPED.toDb())
        if (!lifecycle.sendDesiredState(serverRow, DesiredStatus.STOPPED, force = force)) {
            serverRepository.updateDesiredStatus(id, previous)
            throw BadGatewayException("Agent not connected")
        }
    }

    private suspend fun writeProxyPatch(server: ServerRow) {
        if (!server.serverType.isProxy) return
        val patch = proxyConfigPatchService.generatePatch(server.id) ?: return
        // writeFile's path is resolved relative to the server's data root (bind-mounted to
        // container /server), which IS PATCH_DEFINITIONS' /server — so no dataContainerPath prefix here.
        writeFile(server.id, "craftpanel-patch.json", patch.toByteArray())
    }
}
