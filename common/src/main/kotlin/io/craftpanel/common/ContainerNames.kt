package io.craftpanel.common

/**
 * The single owner of CraftPanel's Docker name convention.
 *
 * Master and the agent both depend on this module so the convention physically cannot drift between
 * the two processes — a drifted copy is exactly what caused the custom-prefix network leak. Only
 * per-server, prefix-derived names live here. Host-global names (the shared `craftpanel` network and
 * the mc-router container) are deliberately **not** prefix-derived and are supplied as explicit
 * config by each process.
 */
class ContainerNames(prefix: String) {

    /** Normalised prefix: trimmed, no trailing separator. */
    val prefix: String = prefix.trim().trimEnd('-').also {
        require(it.isNotEmpty()) { "container name prefix must not be blank" }
    }

    /** `$prefix-$serverId` — the managed server container name. */
    fun container(serverId: String): String = "$prefix-$serverId"

    /** `$prefix-net-$networkId` — the shared per-network bridge (per-server on the agent side). */
    fun sharedNetwork(networkId: String): String = "$prefix-net-$networkId"

    /** `$prefix-server-$serverId` — the standalone per-server bridge. */
    fun standaloneNetwork(serverId: String): String = "$prefix-server-$serverId"

    /** `$prefix-rsync-recv-$migrationId` — the rsync receiver utility container. */
    fun rsyncReceive(migrationId: String): String = "$prefix-rsync-recv-$migrationId"

    /** `$prefix-rsync-send-$migrationId[-final]` — the rsync sender utility container. */
    fun rsyncSend(migrationId: String, final: Boolean): String = "$prefix-rsync-send-$migrationId" + if (final) "-final" else ""

    /**
     * Inverse of [container]. Requires the prefix: a name that is not ours must never be silently
     * mapped to a server id, which would mis-mark the crash gate.
     */
    fun serverIdOf(containerName: String): String {
        require(containerName.startsWith("$prefix-")) {
            "Not a managed container name for prefix '$prefix': $containerName"
        }
        return containerName.removePrefix("$prefix-")
    }

    /** True for any container name this process owns. */
    fun isManagedContainerName(containerName: String): Boolean = containerName.startsWith("$prefix-")

    /** True for the per-server networks this process owns (shared and standalone). */
    fun isManagedNetwork(networkName: String): Boolean = networkName.startsWith("$prefix-net-") || networkName.startsWith("$prefix-server-")

    companion object {
        const val DEFAULT_PREFIX = "craftpanel"
    }
}
