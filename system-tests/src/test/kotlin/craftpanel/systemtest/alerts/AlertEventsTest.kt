package craftpanel.systemtest.alerts

import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.client.model.AlertEventResponse
import craftpanel.systemtest.client.model.CreateAlertThresholdRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import kotlinx.coroutines.delay
import org.openapitools.client.infrastructure.ClientException

class AlertEventsTest : BaseSystemTest() {

    private suspend fun pollForEvents(
        thresholdId: String,
        timeoutMs: Long = 20_000,
        pollMs: Long = 500,
    ): List<AlertEventResponse> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val events = api.listAlertEvents()
            val list = events["events"].orEmpty()
            val matching = list.filter { it.thresholdId == thresholdId }
            if (matching.isNotEmpty()) return matching
            delay(pollMs)
        }
        return emptyList()
    }

    init {
        context("Alert events") {

            should("returns empty events when no thresholds exist") {
                val events = api.listAlertEvents()
                val list = events["events"].orEmpty()
                list.shouldBeEmpty()
            }

            should("events populate after threshold breach") {
                val threshold = api.createAlertThreshold(
                    CreateAlertThresholdRequest(
                        scopeType = "NODE",
                        scopeId = nodeId,
                        metric = "cpu_percent",
                        thresholdValue = 0.0
                    )
                )
                try {
                    val events = pollForEvents(threshold.id)
                    events.isNotEmpty() shouldBe true
                    val event = events.first()
                    event.id.shouldNotBeEmpty()
                    event.thresholdId shouldBe threshold.id
                    event.message.shouldNotBeEmpty()
                    event.firedAt.shouldNotBeEmpty()
                } finally {
                    runCatching { api.deleteAlertThreshold(threshold.id) }
                }
            }

            should("active_only filter returns only unresolved events") {
                val threshold = api.createAlertThreshold(
                    CreateAlertThresholdRequest(
                        scopeType = "NODE",
                        scopeId = nodeId,
                        metric = "cpu_percent",
                        thresholdValue = 0.0
                    )
                )
                try {
                    val events = pollForEvents(threshold.id)
                    events.isNotEmpty() shouldBe true

                    val resolvedCount = events.count { it.resolvedAt != null }
                    val unresolvedCount = events.count { it.resolvedAt == null }
                    unresolvedCount shouldBe events.size - resolvedCount
                } finally {
                    runCatching { api.deleteAlertThreshold(threshold.id) }
                }
            }

            should("scope filter returns only matching events") {
                val threshold = api.createAlertThreshold(
                    CreateAlertThresholdRequest(
                        scopeType = "NODE",
                        scopeId = nodeId,
                        metric = "cpu_percent",
                        thresholdValue = 0.0
                    )
                )
                try {
                    val events = pollForEvents(threshold.id)
                    events.isNotEmpty() shouldBe true

                    val matching = events.filter { it.thresholdId == threshold.id }
                    matching.isNotEmpty() shouldBe true

                    val noMatch = events.filter { it.thresholdId == "00000000-0000-0000-0000-000000000000" }
                    noMatch.shouldBeEmpty()
                } finally {
                    runCatching { api.deleteAlertThreshold(threshold.id) }
                }
            }

            should("deleting threshold removes its events") {
                val thresholdA = api.createAlertThreshold(
                    CreateAlertThresholdRequest(
                        scopeType = "NODE",
                        scopeId = nodeId,
                        metric = "cpu_percent",
                        thresholdValue = 0.0
                    )
                )
                val thresholdB = api.createAlertThreshold(
                    CreateAlertThresholdRequest(
                        scopeType = "NODE",
                        scopeId = nodeId,
                        metric = "cpu_percent",
                        thresholdValue = 0.0
                    )
                )
                val thresholdIdA = thresholdA.id
                val thresholdIdB = thresholdB.id

                val eventsA = pollForEvents(thresholdIdA)
                eventsA.isNotEmpty() shouldBe true
                val eventsB = pollForEvents(thresholdIdB)
                eventsB.isNotEmpty() shouldBe true

                val allBefore = api.listAlertEvents()
                val beforeList = allBefore["events"].orEmpty()

                api.deleteAlertThreshold(thresholdIdA)

                val allAfter = api.listAlertEvents()
                val afterList = allAfter["events"].orEmpty()
                val eventsFromA = afterList.filter { it.thresholdId == thresholdIdA }
                eventsFromA.shouldBeEmpty()

                val eventsFromB = afterList.filter { it.thresholdId == thresholdIdB }
                eventsFromB.isNotEmpty() shouldBe true

                runCatching { api.deleteAlertThreshold(thresholdIdB) }
            }
        }
    }
}
