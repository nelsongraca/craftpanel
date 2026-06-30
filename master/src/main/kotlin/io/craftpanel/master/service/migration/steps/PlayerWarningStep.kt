package io.craftpanel.master.service.migration.steps

import io.craftpanel.master.service.migration.MigrationContext
import io.craftpanel.master.service.migration.MigrationStep
import io.craftpanel.master.service.migration.StepResult
import io.craftpanel.proto.masterMessage
import io.craftpanel.proto.sendRconCommand

class PlayerWarningStep : MigrationStep {

    override val stepNumber = 4
    override val description = "Broadcast player warning via RCON"

    override suspend fun execute(ctx: MigrationContext): StepResult {
        val safeMsg = ctx.playerWarningMessage
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace("\"", "")
            .take(255)
        ctx.gateway.sendToNode(ctx.sourceNodeIdStr, masterMessage {
            sendRcon = sendRconCommand {
                serverId = ctx.serverIdStr
                command = "say $safeMsg"
            }
        })
        return StepResult.Success
    }
}
