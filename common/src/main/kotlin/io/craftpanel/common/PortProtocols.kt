package io.craftpanel.common

/**
 * The single owner of the transport-protocol vocabulary used for container port mappings.
 *
 * A port mapping is stored as one of [TCP], [UDP] or [BOTH]. [BOTH] is a *mapping* concept, not a
 * socket: it means the same host port is published on both transports. The port registry therefore
 * always stores concrete `TCP` / `UDP` rows — callers use [expand] to turn a stored value into the
 * rows (and Docker bindings) it represents.
 *
 * The parser is deliberately total: an unrecognised value falls back to TCP, matching the
 * historical behaviour for servers created before the field existed. Master validates input
 * against [normalize] before persisting, so a fallback in the agent only matters during a
 * master/agent version skew.
 */
object PortProtocols {

    const val TCP = "TCP"
    const val UDP = "UDP"
    const val BOTH = "BOTH"

    /** UI label for [BOTH]; the API and database always use the raw value. */
    const val BOTH_LABEL = "TCP + UDP"

    private val VALID = setOf(TCP, UDP, BOTH)

    /** Returns the canonical uppercase value, or `null` when [raw] is not a supported protocol. */
    fun normalize(raw: String): String? = raw.trim().uppercase().takeIf { it in VALID }

    /**
     * The concrete protocols a mapping uses. `BOTH` expands to `[TCP, UDP]`; anything invalid (or
     * blank) falls back to TCP-only.
     */
    fun expand(raw: String): List<String> = when (normalize(raw)) {
        UDP -> listOf(UDP)
        BOTH -> listOf(TCP, UDP)
        else -> listOf(TCP)
    }

    /** True when the mapping publishes a TCP socket (the default for unknown values). */
    fun hasTcp(raw: String): Boolean = normalize(raw) != UDP

    /** True when the mapping publishes a UDP socket. */
    fun hasUdp(raw: String): Boolean = normalize(raw).let { it == UDP || it == BOTH }
}
