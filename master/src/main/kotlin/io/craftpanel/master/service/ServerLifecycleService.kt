package io.craftpanel.master.service

import io.craftpanel.master.domain.DesiredStatus
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.service.repo.ServerRepository
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
    private val serverHostnames: ServerHostnames,
    private val serverIntent: ServerIntent,
    private val proxyPatchWriter: ProxyPatchWriter
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
        val publicHostname = serverHostnames.mcRouterLabel(serverRow)
        // Write the proxy patch before pushing intent: a failure here must surface loudly and leave
        // the prior intent untouched, not strand the server at a running intent with no process starting.
        proxyPatchWriter.write(serverRow)
        // Re-issuing a start while intent is already RUNNING means recovery from a failed/crash-looped
        // container — force a restart so the agent retries past its exhausted crash budget.
        val forceRestart = desired == DesiredStatus.RUNNING
        serverIntent.withIntent(id, DesiredStatus.RUNNING) {
            lifecycle.sendDesiredState(serverRow, DesiredStatus.RUNNING, forceRestart = forceRestart, publicHostname = publicHostname)
        }
    }

    suspend fun stopServer(id: Uuid) = requestStop(id, force = false)

    suspend fun forceStopServer(id: Uuid) = requestStop(id, force = true)

    suspend fun restartServer(id: Uuid) {
        val serverRow = serverRepository.findById(id) ?: throw NotFoundException("Server not found")
        if (ServerStatus.fromDb(serverRow.status).isStopped) throw ConflictException("Server is not running")
        if (serverRow.isDisabled()) throw ConflictException(serverRow.disabledReason())
        proxyPatchWriter.write(serverRow)
        serverIntent.withIntent(id, DesiredStatus.RUNNING) {
            lifecycle.sendDesiredState(serverRow, DesiredStatus.RUNNING, forceRestart = true, publicHostname = serverHostnames.mcRouterLabel(serverRow))
        }
    }

    private suspend fun requestStop(id: Uuid, force: Boolean) {
        val serverRow = serverRepository.findById(id) ?: throw NotFoundException("Server not found")
        if (ServerStatus.fromDb(serverRow.status) == ServerStatus.STOPPED) {
            throw ConflictException("Server is already stopped")
        }
        serverIntent.withIntent(id, DesiredStatus.STOPPED) {
            lifecycle.sendDesiredState(serverRow, DesiredStatus.STOPPED, force = force)
        }
    }
}
