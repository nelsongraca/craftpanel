package io.craftpanel.master.service

import io.craftpanel.master.database.entity.Server
import io.craftpanel.master.domain.DesiredStatus
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.service.repo.ServerRepository
import io.craftpanel.proto.StartContainerCommand
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Single owner of the per-server "restart pending" marker and of re-pushing a changed runtime spec
 * to the agent.
 *
 * Every spec-feeding mutation runs inside [reconcile]: the runtime spec is built before and after
 * the mutation and, when it changed while the server is RUNNING, the agent's stored spec is
 * refreshed (so the next start / crash-recreate applies it) and the spec is recorded pending. A
 * changed spec on a stopped server needs no push — the next start envelope carries the current spec.
 *
 * [restartRequired] is the marker-only sibling for changes that need a restart but do not alter the
 * container spec (e.g. proxy config files written into the data dir).
 *
 * Keeping both the marker and the push in one place is deliberate: the original bug was a config
 * writer setting the marker without re-pushing the spec, so the agent never learned it changed and
 * the next restart recreated from the stale spec.
 */
class ServerSpecSync(private val lifecycle: ContainerLifecycle, private val serverRepository: ServerRepository) {

    /**
     * Runs [mutate] and, if it changed the server's runtime spec, flags a pending restart and
     * re-pushes the spec to a RUNNING server. Returns [mutate]'s result unchanged.
     */
    fun <T> reconcile(serverId: Uuid, mutate: () -> T): T {
        val before = serverRepository.findById(serverId)?.let { normalize(lifecycle.buildStartSpec(it)) }
        val result = mutate()
        val after = serverRepository.findById(serverId) ?: return result
        if (before == normalize(lifecycle.buildStartSpec(after))) return result
        // "Running" is intent OR a running agent report: a server whose intent was never recorded
        // but which the agent reports HEALTHY (e.g. adopted on reconnect) still needs the refreshed
        // spec pushed so its next restart applies the change.
        val running = DesiredStatus.fromDb(after.desiredStatus) == DesiredStatus.RUNNING ||
            ServerStatus.fromDb(after.status).isRunning
        val delivered = if (running) {
            lifecycle.refreshRunningSpec(after)
        } else {
            // No push (the server is not running); the next start envelope carries the new spec.
            true
        }
        transaction {
            Server.findById(serverId)?.let {
                it.restartPending = true
                it.specDelivered = delivered
            }
        }
        return result
    }

    /** Flags a pending restart without touching the agent's stored spec (file-only changes). */
    fun restartRequired(serverId: Uuid) {
        transaction { Server.findById(serverId)?.let { it.restartPending = true } }
    }

    /** Records whether the agent is known to hold the server's current spec. */
    fun markDelivered(serverId: Uuid, delivered: Boolean) {
        transaction { Server.findById(serverId)?.let { it.specDelivered = delivered } }
    }

    /**
     * Order-independent view of the spec so two consecutive builds of the same row compare equal.
     * The env map already compares order-independently; only the repeated extra-port field needs
     * normalizing (its DB read order is not guaranteed).
     */
    private fun normalize(cmd: StartContainerCommand): StartContainerCommand = cmd.toBuilder()
        .clearExtraPorts()
        .addAllExtraPorts(
            cmd.extraPortsList.sortedWith(
                compareBy({ it.protocol }, { it.hostPort }, { it.containerPort }, { it.name })
            )
        )
        .build()
}
