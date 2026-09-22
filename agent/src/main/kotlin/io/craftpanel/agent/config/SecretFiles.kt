package io.craftpanel.agent.config

import io.craftpanel.common.secretFileContents
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("io.craftpanel.agent.config.SecretFiles")

/**
 * Resolves a secret with the Docker/Kubernetes `_FILE` convention.
 *
 * Precedence: `<envName>_FILE` (file contents) > plain env value > default.
 * The `_FILE` read + validation is shared with master via [secretFileContents].
 */
fun secretFromFileOrEnv(envName: String, default: String, env: (String) -> String? = System::getenv): String {
    secretFileContents(envName, env)?.let {
        log.info("Loaded secret $envName from ${envName}_FILE")
        return it
    }
    return env(envName) ?: default
}
