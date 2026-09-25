package io.craftpanel.agent.desired

/** Default JVM-metrics poll interval, matching the master's `jvm_metrics_poll_interval_seconds`. */
const val DEFAULT_JVM_METRICS_POLL_INTERVAL_SECONDS = 30

/**
 * Resolved JVM-metrics policy for one server: whether to sample its heap, and how often. Built from
 * the desired-state envelope — `enabled` is per-server (`servers.jvm_metrics_enabled`),
 * `pollIntervalSeconds` is a global system setting. Both are read live, so a change takes effect on
 * the next envelope without recreating the container.
 */
data class JvmMetricsPolicy(val enabled: Boolean, val pollIntervalSeconds: Int)
