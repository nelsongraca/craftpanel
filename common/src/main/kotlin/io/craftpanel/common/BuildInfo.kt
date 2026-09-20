package io.craftpanel.common

import java.util.Properties

/**
 * Build metadata baked into this jar at build time (see `common/build.gradle.kts`).
 *
 * Read from the artifact rather than the environment on purpose: the value must reflect the
 * exact commit the running image was built from, so it cannot be spoofed by an env var or a
 * mounted file. Master surfaces it on `/health`; the agent reports it to master on connect.
 */
object BuildInfo {

    val version: String = readVersion()

    private fun readVersion(): String {
        val stream = BuildInfo::class.java.getResourceAsStream("/build-info.properties") ?: return "unknown"
        return stream.use {
            val props = Properties()
            props.load(it)
            props.getProperty("version")?.takeIf { v -> v.isNotBlank() } ?: "unknown"
        }
    }
}
