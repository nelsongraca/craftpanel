package io.craftpanel.master.config

import io.craftpanel.master.crypto.SecretCipher
import io.craftpanel.master.database.schema.SystemSettings
import io.craftpanel.master.service.EncryptedSettingValue
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.slf4j.LoggerFactory
import kotlin.time.Clock

private val log = LoggerFactory.getLogger("io.craftpanel.master.config.DnsEnvBackfill")

/**
 * TEMPORARY upgrade aid — delete this file and its single call site in Main after the deploy wave.
 *
 * DNS configuration moved from environment variables to database settings. To make an in-place
 * upgrade lossless, seed the new `dns_provider` / `cf_api_token` rows from the old env vars the first
 * time master starts with them still present.
 *
 * Only a missing row is seeded, so a value an operator has since saved through the UI is never
 * clobbered by a stale environment variable on a later restart. `CF_API_TOKEN_FILE` is honoured via
 * [secretFromFileOrValue], matching the previous resolution order.
 */
fun backfillDnsSettingsFromEnv(forwardingKey: String) {
    val provider = System.getenv("DNS_PROVIDER")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    val token = secretFromFileOrValue("CF_API_TOKEN", System.getenv("CF_API_TOKEN") ?: "")
        .takeIf { it.isNotBlank() }
    if (provider == null && token == null) return

    val cipher = token?.let {
        runCatching {
            SecretCipher(
                java.util.Base64.getDecoder()
                    .decode(forwardingKey)
            )
        }
            .onFailure { e -> log.error("Cannot encrypt migrated CF_API_TOKEN — check FORWARDING_KEY", e) }
            .getOrNull()
    }

    val now = Clock.System.now()
        .toLocalDateTime(TimeZone.UTC)
    transaction {
        if (provider != null && SystemSettings.selectAll()
                .where { SystemSettings.key eq "dns_provider" }
                .empty()
        ) {
            SystemSettings.upsert {
                it[SystemSettings.key] = "dns_provider"
                it[SystemSettings.value] = provider
                it[SystemSettings.updatedAt] = now
            }
            log.warn("Migrated DNS_PROVIDER env to settings — this env fallback is temporary and will be removed")
        }
        if (token != null && cipher != null && SystemSettings.selectAll()
                .where { SystemSettings.key eq "cf_api_token" }
                .empty()
        ) {
            SystemSettings.upsert {
                it[SystemSettings.key] = "cf_api_token"
                it[SystemSettings.value] = EncryptedSettingValue.encrypt(cipher, token)
                it[SystemSettings.updatedAt] = now
            }
            log.warn("Migrated CF_API_TOKEN env to settings — this env fallback is temporary and will be removed")
        }
    }
}
