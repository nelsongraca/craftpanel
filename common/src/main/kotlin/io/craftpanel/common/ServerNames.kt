package io.craftpanel.common

/**
 * The single owner of CraftPanel's server-name rule. A server name doubles as the managed
 * container's Docker hostname, and therefore as a DNS name on the shared network, so it must be a
 * valid DNS label: lowercase alphanumerics and hyphens, not starting with a hyphen, at most
 * [MAX_LENGTH] characters.
 *
 * Master enforces this at provisioning time so the agent can pass the name straight to Docker
 * without any lossy normalisation.
 */
object ServerNames {

    /** DNS-label limit (also the usable Linux `HOST_NAME_MAX`): the longest a hostname label may be. */
    const val MAX_LENGTH = 63

    private val PATTERN = Regex("^[a-z0-9][a-z0-9-]*$")

    /** True when [name] is a valid server name — a DNS-safe lowercase slug of at most [MAX_LENGTH]. */
    fun isValid(name: String): Boolean = name.length in 1..MAX_LENGTH && PATTERN.matches(name)

    /**
     * True when [name] collides with a Docker object name derived from [containerPrefix] — server
     * containers (`$prefix-<id>`), utility containers (`$prefix-mc-router`) and networks
     * (`$prefix-net-*`). The prefix is configuration-driven, so it is supplied by the caller rather
     * than assumed to be the default.
     */
    fun collidesWithPrefix(name: String, containerPrefix: String): Boolean {
        val prefix = containerPrefix.trim().trimEnd('-')
        return prefix.isNotEmpty() && (name == prefix || name.startsWith("$prefix-"))
    }
}
