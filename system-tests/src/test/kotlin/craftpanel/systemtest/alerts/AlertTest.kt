package craftpanel.systemtest.alerts

import craftpanel.systemtest.client.model.CreateAlertThresholdRequest
import craftpanel.systemtest.client.model.ScopeType
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException

class AlertTest : BaseSystemTest() {

    init {
        context("Alert thresholds") {

            lateinit var thresholdId: String

            should("creates a NODE-scoped alert threshold") {
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

            should("lists alert thresholds includes created threshold") {
                val thresholds = api.listAlertThresholds()
                thresholds.thresholds.map { it.id } shouldContain thresholdId
            }

            should("deletes alert threshold") {
                api.deleteAlertThreshold(thresholdId)
                val thresholds = api.listAlertThresholds()
                thresholds.thresholds.map { it.id } shouldNotContain thresholdId
            }

            should("deleting non-existent threshold returns 404") {
                val ex = shouldThrow<ClientException> {
                    api.deleteAlertThreshold("00000000-0000-0000-0000-000000000000")
                }
                ex.statusCode shouldBe 404
            }

            should("creating threshold with invalid scope_type returns 422") {
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
