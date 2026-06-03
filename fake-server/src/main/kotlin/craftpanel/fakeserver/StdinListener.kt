package craftpanel.fakeserver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/**
 * Reads from stdin line by line.
 *
 * Two roles:
 *   1. Logs every command received — test assertions can inspect container logs to verify
 *      that master/agent sent the correct stop_command before Docker stop.
 *      e.g. assertThat(container.logs).contains("[fake-server] stdin received: stop")
 *
 *   2. Exits the process cleanly when the configured stop_command is received,
 *      matching the behaviour of a real Minecraft server responding to `stop` or `end`.
 *
 * The stop_command is configurable via STOP_COMMAND env var (default: "stop").
 * Proxy fake server would set STOP_COMMAND=end to match Velocity/BungeeCord behaviour.
 */
class StdinListener(private val config: Config) {

    fun start(scope: CoroutineScope) {
        log("stdin listener ready (stop command: \"${config.stopCommand}\")")
        val reader = System.`in`.bufferedReader()

        try {
            for (line in reader.lineSequence()) {
                val trimmed = line.trim()
                // Log in a format that's easy to grep in test assertions
                log("stdin received: $trimmed")

                when (trimmed) {
                    config.stopCommand -> {
                        log("stop command received — shutting down cleanly")
                        scope.cancel()
                        // Small delay so the log line above flushes before exit
                        Thread.sleep(200)
                        Runtime.getRuntime().halt(0)
                    }
                    // itzg sends save-all / save-off / save-on during backup and migration
                    "save-all"  -> log("save-all acknowledged")
                    "save-off"  -> log("save-off acknowledged — auto-save disabled")
                    "save-on"   -> log("save-on acknowledged — auto-save enabled")
                    else        -> log("command acknowledged: $trimmed")
                }
            }
        } catch (e: Exception) {
            log("stdin closed: ${e.message}")
        }
    }
}
