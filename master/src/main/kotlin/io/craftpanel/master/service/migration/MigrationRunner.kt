package io.craftpanel.master.service.migration

import io.craftpanel.master.domain.MigrationStatus
import io.craftpanel.master.service.MigrationEvent
import io.craftpanel.proto.removeContainerCommand
import io.craftpanel.proto.masterMessage
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

class MigrationRunner(
    private val steps: List<MigrationStep>,
    private val ctx: MigrationContext,
) {

    suspend fun run() {
        try {
            for (step in steps) {
                val stepId = ctx.startStep(step.stepNumber, step.description)
                val result = step.execute(ctx)
                when (result) {
                    is StepResult.Success -> ctx.completeStep(stepId, true)
                    is StepResult.Failure -> {
                        ctx.completeStep(stepId, false, result.error)
                        ctx.failMigration(result.error)
                        return
                    }
                }
                if (step.stepNumber == 4) delay(2.seconds)
            }
            ctx.updateStatus(MigrationStatus.COMPLETED)
            ctx.emit(MigrationEvent.Completed)
        }
        finally {
            runCatching {
                ctx.gateway.sendToNode(ctx.targetNodeIdStr, masterMessage {
                    removeContainer = removeContainerCommand {
                        containerName = "${ctx.containerNamePrefix}-rsync-recv-${ctx.migrationIdStr}"
                        force = true
                    }
                })
            }
            ctx.serverRepository.releasePort(ctx.targetNodeId, ctx.rsyncPort, "TCP")
        }
    }
}
