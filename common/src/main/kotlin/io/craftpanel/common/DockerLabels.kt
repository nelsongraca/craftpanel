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
}
