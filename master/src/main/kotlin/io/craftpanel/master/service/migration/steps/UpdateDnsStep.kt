package io.craftpanel.master.service.migration.steps

import io.craftpanel.master.service.ServerExposure
import io.craftpanel.master.service.migration.MigrationContext
import io.craftpanel.master.service.migration.MigrationStep
import io.craftpanel.master.service.migration.StepResult

class UpdateDnsStep(private val serverExposure: ServerExposure) : MigrationStep {

    override val stepNumber = 9
    override val description = "Update DNS A record to target node IP"

    override suspend fun execute(ctx: MigrationContext): StepResult {
        val recordId = ctx.serverRow.dnsRecordId
        val provider = ctx.dnsProvider
        if (recordId != null && provider != null) {
            val networkId = ctx.serverRow.networkId
            val dns = serverExposure.resolveNetworkDns(networkId)
            if (dns != null) {
                return runCatching {
                    provider.updateARecord(dns.zoneId, recordId, ctx.targetNodeRow.publicIp)
                    StepResult.Success
                }.getOrElse { e ->
                    StepResult.Failure("DNS update failed: ${e.message}")
                }
            }
        }
        return StepResult.Success
    }
}
