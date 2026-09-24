package io.craftpanel.common

/**
 * Docker label vocabulary shared by master and agent, so the marker they tag and query managed
 * resources with cannot drift between the two processes (the same rationale as [ContainerNames]).
 */
object DockerLabels {

    /** Marks a resource (container, network, mc-router) as owned by CraftPanel. */
    const val MANAGED = "craftpanel.managed"

    /** Value of [MANAGED] on every managed resource. */
    const val MANAGED_VALUE = "true"

    /**
     * Records the exact env-var keys master set on a container at create time (sorted, comma-joined).
     * The spec-diff reads it back to detect a *removed* key — the container still carries the stale
     * value, which a subset check alone cannot see. Image/daemon-provided keys are never included.
     */
    const val MANAGED_ENV_KEYS = "craftpanel.managed-env-keys"
}
