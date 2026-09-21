package io.craftpanel.master.service

import io.craftpanel.master.service.repo.SettingsEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.uuid.Uuid

class SettingsTest :
    FunSpec({

        fun entry(key: String, value: String) = SettingsEntry(key, value, "2025-01-01T00:00:00Z", null)

        test("empty rows yield the built-in defaults") {
            val settings = Settings.from(emptyList())

            settings.appName shouldBe "CraftPanel"
            settings.appLogo shouldBe null
            settings.metricRetentionDays shouldBe 30
            settings.defaultPortRangeStart shouldBe 25570
            settings.defaultPortRangeEnd shouldBe 26070
            settings.restartMaxAttempts shouldBe 5
            settings.restartWindowSeconds shouldBe 600L
            settings.imageMinecraft shouldBe "itzg/minecraft-server"
            settings.imageProxy shouldBe "itzg/mc-proxy"
            settings.consoleTailLines shouldBe 200
            settings.dnsDomainSuffix shouldBe null
            settings.dnsZoneId shouldBe null
        }

        test("stored values override the defaults") {
            val settings = Settings.from(
                listOf(
                    entry("app_name", "My Panel"),
                    entry("metric_retention_days", "7"),
                    entry("image_minecraft", "itzg/custom"),
                    entry("dns_domain_suffix", "example.com"),
                    entry("dns_zone_id", "zone-1"),
                )
            )

            settings.appName shouldBe "My Panel"
            settings.metricRetentionDays shouldBe 7
            settings.imageMinecraft shouldBe "itzg/custom"
            settings.dnsDomainSuffix shouldBe "example.com"
            settings.dnsZoneId shouldBe "zone-1"
        }

        test("blank strings mean unset for nullable fields and fall back for appName") {
            val settings = Settings.from(
                listOf(
                    entry("app_name", "   "),
                    entry("app_logo", ""),
                    entry("dns_domain_suffix", " "),
                    entry("dns_zone_id", ""),
                )
            )

            settings.appName shouldBe "CraftPanel"
            settings.appLogo shouldBe null
            settings.dnsDomainSuffix shouldBe null
            settings.dnsZoneId shouldBe null
        }

        test("non-numeric values fall back to the default") {
            val settings = Settings.from(
                listOf(
                    entry("metric_retention_days", "not-a-number"),
                    entry("restart_window_seconds", "soon"),
                )
            )

            settings.metricRetentionDays shouldBe 30
            settings.restartWindowSeconds shouldBe 600L
        }

        test("app_logo is passed through when set") {
            val logo = "data:image/png;base64,AAAA"

            Settings.from(listOf(entry("app_logo", logo))).appLogo shouldBe logo
        }

        test("updatedBy is ignored by the typed snapshot") {
            val rows = listOf(SettingsEntry("app_name", "Panel", "2025-01-01T00:00:00Z", Uuid.random()))

            Settings.from(rows).appName shouldBe "Panel"
        }
    })
