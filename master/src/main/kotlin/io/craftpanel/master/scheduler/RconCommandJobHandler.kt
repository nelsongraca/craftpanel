package io.craftpanel.master.scheduler

import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.service.AgentGateway
import io.craftpanel.master.service.repo.ServerRepository
import io.craftpanel.proto.masterMessage
import io.craftpanel.proto.sendRconCommand
import org.slf4j.LoggerFactory

/**
 * Fires an arbitrary console command at a running server via RCON. The agent executes
 * `rcon-cli <command>` inside the container (fire-and-forget — no result is reported back), so the
 * server must already be `HEALTHY`; a stopped or unhealthy server is skipped with a warning.
 */
class RconCommandJobHandler(private val serverRepository: ServerRepository, private val gateway: AgentGateway) : ScheduledJobHandler {

    private val log = LoggerFactory.getLogger(RconCommandJobHandler::class.java)
    override val jobType = "RCON_COMMAND"

    override suspend fun execute(context: JobExecutionContext) {
        val command = context.payload?.trim()
        if (command.isNullOrEmpty()) {
            log.warn("Scheduled RCON job for server ${context.serverId} has no command — skipping")
            return
        }

        val server = serverRepository.findById(context.serverId)
        if (server == null) {
            log.warn("Scheduled RCON job for unknown server ${context.serverId} — skipping")
            return
        }
        if (ServerStatus.fromDb(server.status) != ServerStatus.HEALTHY) {
            log.warn("Scheduled RCON job for server ${context.serverId} skipped: status is ${server.status}")
            return
        }

        log.info("Scheduled RCON firing for server ${context.serverId}: $command")
        val sent = gateway.sendToNode(
            server.nodeId.toString(),
            masterMessage {
                sendRcon = sendRconCommand {
                    serverId = context.serverId.toString()
                    this.command = command
                }
            }
        )
        if (!sent) log.warn("Scheduled RCON job for server ${context.serverId} not delivered — agent disconnected")
    }
}
