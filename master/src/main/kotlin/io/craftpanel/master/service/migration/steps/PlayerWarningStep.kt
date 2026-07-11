package io.craftpanel.master.service.migration.steps

import io.craftpanel.master.service.migration.*
import io.craftpanel.proto.masterMessage
import io.craftpanel.proto.sendRconCommand

class PlayerWarningStep : MigrationStep {

    override val stepNumber = 4
    override val description = "Broadcast player warning via RCON"

    override suspend fun execute(plan: MigrationPlan, coord: MigrationCoordinator): StepResult {
        val safeMsg = plan.playerWarningMessage
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace("\"", "")
            .take(255)
        coord.gateway.sendToNode(
            plan.sourceNodeIdStr,
            masterMessage {
                sendRcon = sendRconCommand {
                    serverId = plan.serverIdStr
                    command = "say $safeMsg"
                }
            }
        )
        return StepResult.Success
    }
}
