package craftpanel.systemtest.system

import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.client.model.PatchSettingsRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException

class SystemSettingsTest : BaseSystemTest() {

    init {
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
