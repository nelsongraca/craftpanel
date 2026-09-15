package io.craftpanel.master.domain

/**
 * Master's intent for a server, mirrored from `servers.desired_status`. `null` means "unset" — no
 * start/stop intent has been recorded (e.g. a freshly imported server), in which case the reported
 * status is surfaced verbatim.
 */
enum class DesiredStatus {

    RUNNING,
    STOPPED;

    fun toDb() = name

    companion object {

        fun fromDb(s: String?): DesiredStatus? = s?.let { valueOf(it) }
    }
}

/**
 * Read-time status synthesis — never persisted. Combines master's [desired] intent with the
 * agent-[reported] status to produce the status surfaced via the API, so transient states such as
 * STARTING/STOPPING are derived rather than written to the DB (the old write-then-wedge class is
 * therefore impossible).
 *
 * | desired | reported                | shown        |
 * |---------|-------------------------|--------------|
 * | RUNNING | HEALTHY                 | HEALTHY      |
 * | RUNNING | STARTING                | STARTING     |
 * | RUNNING | STOPPED                 | STARTING     |
 * | RUNNING | UNHEALTHY               | UNHEALTHY    |
 * | RUNNING | CRASH_LOOPED            | CRASH_LOOPED |
 * | STOPPED | STOPPED                 | STOPPED      |
 * | STOPPED | STARTING                | STOPPING     |
 * | STOPPED | HEALTHY / UNHEALTHY     | STOPPING     |
 * | STOPPED | CRASH_LOOPED            | STOPPED      |
 * | unset   | *                       | reported     |
 */
fun synthesizeStatus(desired: DesiredStatus?, reported: ServerStatus): ServerStatus = when (desired) {
    null -> reported

    DesiredStatus.RUNNING -> when (reported) {
        // Intent is up but the container is not (yet) — it is (re)starting, not stopped.
        ServerStatus.STOPPED -> ServerStatus.STARTING
        else -> reported
    }

    DesiredStatus.STOPPED -> when (reported) {
        ServerStatus.STOPPED, ServerStatus.CRASH_LOOPED -> ServerStatus.STOPPED
        else -> ServerStatus.STOPPING
    }
}
