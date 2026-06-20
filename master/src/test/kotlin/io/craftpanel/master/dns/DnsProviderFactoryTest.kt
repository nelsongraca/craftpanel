package io.craftpanel.master.dns

import io.craftpanel.master.config.DnsConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class DnsProviderFactoryTest : FunSpec({

    test("cloudflare provider with valid token returns CloudflareDnsProvider") {
        val provider = DnsProviderFactory.create(DnsConfig(provider = "cloudflare", cloudflareApiToken = "tok123"))
        provider.shouldBeInstanceOf<CloudflareDnsProvider>()
    }

    test("cloudflare provider is case-insensitive") {
        val provider = DnsProviderFactory.create(DnsConfig(provider = "Cloudflare", cloudflareApiToken = "tok"))
        provider.shouldBeInstanceOf<CloudflareDnsProvider>()
    }

    test("none provider returns null") {
        DnsProviderFactory.create(DnsConfig(provider = "none", cloudflareApiToken = "")) shouldBe null
    }

    test("empty provider string returns null") {
        DnsProviderFactory.create(DnsConfig(provider = "", cloudflareApiToken = "")) shouldBe null
    }

    test("cloudflare with blank token throws") {
        shouldThrow<IllegalArgumentException> {
            DnsProviderFactory.create(DnsConfig(provider = "cloudflare", cloudflareApiToken = ""))
        }
    }

    test("unknown provider throws") {
        shouldThrow<IllegalStateException> {
            DnsProviderFactory.create(DnsConfig(provider = "route53", cloudflareApiToken = ""))
        }
    }
})
