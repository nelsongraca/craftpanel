package io.craftpanel.master.service

import io.craftpanel.master.auth.ScopeType
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.service.repo.AlertRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Evaluates alert thresholds against a sampled metric snapshot: opens an alert
 * event when a metric crosses its threshold and resolves it when the metric
 * normalises. Side effects are limited to [AlertRepository] writes — callers
 * emit the returned notifications.
 */
class AlertEvaluator(private val alertRepository: AlertRepository, private val clock: Clock = Clock.System) {

    fun evaluate(scopeType: ScopeType, scopeId: Uuid, scopeLabel: String, metricValues: Map<String, Double>): List<AgentEvent.AlertFiredEvent> {
        val now = clock.now()
        val notifications = mutableListOf<AgentEvent.AlertFiredEvent>()
        val thresholds = alertRepository.listThresholds(scopeType.name, scopeId)
            .filter { it.thresholdValue != null }

        for (threshold in thresholds) {
            val thresholdId = threshold.id
            val metric = threshold.metric
            val limitValue = threshold.thresholdValue ?: continue
            val currentValue = metricValues[metric] ?: continue
            val triggered = currentValue > limitValue
            val openEvent = alertRepository.findOpenEvent(thresholdId)

            if (triggered && openEvent == null) {
                val msg = "$scopeLabel: $metric at ${"%.1f".format(currentValue)}%"
                val newEvent = alertRepository.createEvent(thresholdId, msg)
                notifications += AgentEvent.AlertFiredEvent(
                    eventId = newEvent.id.toString(),
                    thresholdId = thresholdId.toString(),
                    scopeType = scopeType.name,
                    scopeId = scopeId.toString(),
                    metric = metric,
                    message = msg,
                    firedAt = newEvent.firedAt,
                    resolvedAt = null
                )
            } else if (!triggered && openEvent != null) {
                alertRepository.resolveEventsForThreshold(thresholdId, now)
                val msg = "$scopeLabel: $metric normalised"
                notifications += AgentEvent.AlertFiredEvent(
                    eventId = openEvent.id.toString(),
                    thresholdId = thresholdId.toString(),
                    scopeType = scopeType.name,
                    scopeId = scopeId.toString(),
                    metric = metric,
                    message = msg,
                    firedAt = openEvent.firedAt,
                    resolvedAt = now.toString()
                )
            }
        }

        return notifications
    }
}
