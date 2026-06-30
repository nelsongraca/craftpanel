package io.craftpanel.master.service.migration.steps

import io.craftpanel.master.service.migration.MigrationContext
import io.craftpanel.master.service.migration.MigrationStep
import io.craftpanel.master.service.migration.StepResult

class StartTargetContainerStep : MigrationStep {

    override val stepNumber = 8
    override val description = "Create and start server container on target node"

    override suspend fun execute(ctx: MigrationContext): StepResult {
        val server = ctx.freshServerRow ?: return StepResult.Failure("Server row not available")
        return runCatching {
            ctx.lifecycle.start(server, needsRecreate = true, nodeId = ctx.targetNodeIdStr)
            StepResult.Success
        }.getOrElse { e ->
            runCatching { ctx.lifecycle.remove(server, ctx.targetNodeIdStr, force = true) }
            StepResult.Failure("New container failed to start on target: ${e.message}")
        }
    }
}
