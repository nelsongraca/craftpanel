package io.craftpanel.agent.desired

/**
 * Resolved JVM-metrics policy for one server: whether to sample its heap, and how often. `enabled` is
 * per-server (`servers.jvm_metrics_enabled`, read from the desired-state envelope);
 * `pollIntervalSeconds` is install-wide and read live from
 * [io.craftpanel.agent.config.RuntimeSettingsStore], so retuning it takes effect on the next tick
 * without recreating the container.
 */
data class JvmMetricsPolicy(val enabled: Boolean, val pollIntervalSeconds: Int)
