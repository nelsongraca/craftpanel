package io.craftpanel.agent.config

import io.craftpanel.proto.AgentRuntimeSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Install-wide agent runtime tuning pushed by master. Defaults mirror master's `Settings` defaults
 * exactly, so a fresh agent — or one talking to a master that predates the snapshot — behaves
 * precisely as before.
 */
@Serializable
data class RestartBudgetSettings(val maxAttempts: Int = 5, val windowSeconds: Long = 600)

@Serializable
data class RuntimeSettings(
    val metricsPollIntervalSeconds: Int = 5,
    val metricsCollectionConcurrency: Int = 8,
    // 0 disables the convergence backstop sweep.
    val reconcileIntervalSeconds: Int = 30,
    val restartBudget: RestartBudgetSettings = RestartBudgetSettings(),
    val jvmMetricsPollIntervalSeconds: Int = 30
) {

    companion object {

        val DEFAULTS = RuntimeSettings()

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Converts a pushed snapshot. Returns null for an all-zero message — the wire signal for
         * "this master predates runtime settings", which must leave the cached/default values alone.
         * `reconcileIntervalSeconds = 0` is a legitimate value from a current master, so the guard is
         * on the *whole* snapshot being zero, not on that field.
         */
        fun fromProto(proto: AgentRuntimeSettings): RuntimeSettings? {
            val allZero = proto.metricsPollIntervalSeconds == 0 &&
                proto.metricsCollectionConcurrency == 0 &&
                proto.reconcileIntervalSeconds == 0 &&
                proto.jvmMetricsPollIntervalSeconds == 0 &&
                proto.restartBudget.maxAttempts == 0 &&
                proto.restartBudget.windowSeconds == 0L
            if (allZero) return null
            return RuntimeSettings(
                metricsPollIntervalSeconds = proto.metricsPollIntervalSeconds.coerceAtLeast(1),
                metricsCollectionConcurrency = proto.metricsCollectionConcurrency.coerceAtLeast(1),
                reconcileIntervalSeconds = proto.reconcileIntervalSeconds.coerceAtLeast(0),
                restartBudget = RestartBudgetSettings(
                    maxAttempts = proto.restartBudget.maxAttempts.coerceAtLeast(0),
                    windowSeconds = proto.restartBudget.windowSeconds.coerceAtLeast(1)
                ),
                jvmMetricsPollIntervalSeconds = proto.jvmMetricsPollIntervalSeconds.coerceAtLeast(1)
            )
        }

        fun decode(raw: String): RuntimeSettings? = runCatching { json.decodeFromString<RuntimeSettings>(raw) }.getOrNull()

        fun encode(settings: RuntimeSettings): String = json.encodeToString(settings)
    }
}
