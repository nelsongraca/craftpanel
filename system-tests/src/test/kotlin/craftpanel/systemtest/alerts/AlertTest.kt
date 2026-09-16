package craftpanel.systemtest.alerts

import craftpanel.systemtest.client.model.CreateAlertThresholdRequest
import craftpanel.systemtest.client.model.ScopeType
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException

@Tags("BackupAlerts")
class AlertTest : BaseSystemTest() {

    init {
        context("Alert thresholds") {

            lateinit var thresholdId: String

            should("create a NODE-scoped alert threshold") {
                val threshold = api.createAlertThreshold(
                    CreateAlertThresholdRequest(
                        scopeType = ScopeType.NODE,
                        scopeId = nodeId,
                        metric = "cpu_percent",
                        thresholdValue = 90.0
                    )
                )
                thresholdId = threshold.id
                threshold.metric shouldBe "cpu_percent"
            }

            should("list alert thresholds including the created one") {
                val thresholds = api.listAlertThresholds()
                thresholds.thresholds.map { it.id } shouldContain thresholdId
            }

            should("delete an alert threshold") {
                api.deleteAlertThreshold(thresholdId)
                val thresholds = api.listAlertThresholds()
                thresholds.thresholds.map { it.id } shouldNotContain thresholdId
            }

            should("return 404 when deleting a non-existent threshold") {
                val ex = shouldThrow<ClientException> {
                    api.deleteAlertThreshold("00000000-0000-0000-0000-000000000000")
                }
                ex.statusCode shouldBe 404
            }

            should("return 422 when creating a threshold with an invalid scope_type") {
                val ex = shouldThrow<ClientException> {
                    api.createAlertThreshold(
                        CreateAlertThresholdRequest(
                            scopeType = ScopeType.GLOBAL,
                            scopeId = "00000000-0000-0000-0000-000000000000",
                            metric = "cpu_percent",
                            thresholdValue = 90.0
                        )
                    )
                }
                ex.statusCode shouldBe 422
            }
        }
    }
}
