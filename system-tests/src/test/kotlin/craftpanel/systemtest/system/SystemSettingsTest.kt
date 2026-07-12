package craftpanel.systemtest.system

import craftpanel.systemtest.client.model.PatchSettingsRequest
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.core.annotation.Isolate
import io.kotest.core.annotation.Tags
import io.kotest.matchers.shouldBe

@Isolate
@Tags("Misc")
class SystemSettingsTest : BaseSystemTest() {

    init {
        var originalMetricRetentionDays = 0
        var originalBackupMaxCount = 0
        var originalPortRangeStart = 0
        var originalPortRangeEnd = 0

        beforeSpec {
            val s = api.getSystemSettings().settings
            originalMetricRetentionDays = s.metricRetentionDays
            originalBackupMaxCount = s.defaultBackupMaxCount
            originalPortRangeStart = s.defaultPortRangeStart
            originalPortRangeEnd = s.defaultPortRangeEnd
        }

        afterSpec {
            api.updateSystemSettings(
                PatchSettingsRequest(
                    metricRetentionDays = originalMetricRetentionDays,
                    defaultBackupMaxCount = originalBackupMaxCount,
                    defaultPortRangeStart = originalPortRangeStart,
                    defaultPortRangeEnd = originalPortRangeEnd,
                )
            )
        }

        context("System settings") {

            should("gets system settings with defaults") {
                val response = api.getSystemSettings()
                response.settings.metricRetentionDays shouldBe 30
            }

            should("updates system settings") {
                val updated = api.updateSystemSettings(
                    PatchSettingsRequest(metricRetentionDays = 60)
                )
                updated.settings.metricRetentionDays shouldBe 60
            }

            should("updates backup and port settings") {
                val updated = api.updateSystemSettings(
                    PatchSettingsRequest(
                        defaultBackupMaxCount = 10,
                        defaultPortRangeStart = 26000,
                        defaultPortRangeEnd = 26100
                    )
                )
                updated.settings.defaultBackupMaxCount shouldBe 10
                updated.settings.defaultPortRangeStart shouldBe 26000
                updated.settings.defaultPortRangeEnd shouldBe 26100
            }
        }
    }
}
