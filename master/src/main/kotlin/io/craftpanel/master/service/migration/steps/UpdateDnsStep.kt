package io.craftpanel.master.service.migration.steps

import io.craftpanel.master.service.migration.*

class UpdateDnsStep : MigrationStep {

    override val stepNumber = 9
    override val description = "Update DNS A record to target node IP"

    override suspend fun execute(plan: MigrationPlan, coord: MigrationCoordinator): StepResult {
        val recordId = plan.serverRow.dnsRecordId
        val provider = coord.dnsProvider
        if (recordId != null && provider != null) {
            val dns = coord.resolveTargetDns(plan)
            if (dns != null) {
                return runCatching {
                    provider.updateARecord(dns.zoneId, recordId, plan.targetNodeRow.publicIp)
                    StepResult.Success
                }.getOrElse { e ->
                    StepResult.Failure("DNS update failed: ${e.message}")
                }
            }
        }
        return StepResult.Success
    }
}
