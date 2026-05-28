package io.craftpanel.master.dns

import io.craftpanel.master.config.DnsConfig

object DnsProviderFactory {

    fun create(config: DnsConfig): DnsProvider? = when (config.provider.lowercase()) {
        "cloudflare" -> {
            require(config.cloudflareApiToken.isNotBlank()) {
                "CF_API_TOKEN must be set when DNS_PROVIDER=cloudflare"
            }
            CloudflareDnsProvider(config.cloudflareApiToken)
        }

        "none", ""   -> null
        else         -> error("Unknown DNS provider: ${config.provider}")
    }
}
