package io.craftpanel.master.config

import io.craftpanel.common.secretFileContents
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("io.craftpanel.master.config.SecretFiles")

/**
 * Resolves a secret with the Docker/Kubernetes `_FILE` convention: when `<envName>_FILE` is set and
 * readable, its trimmed contents win over [value] (the already-resolved config value).
 * The `_FILE` read + validation is shared with the agent via [secretFileContents].
 */
fun secretFromFileOrValue(envName: String, value: String, getenv: (String) -> String? = System::getenv): String {
    secretFileContents(envName, getenv)?.let {
        log.info("Loaded secret $envName from ${envName}_FILE")
        return it
    }
    return value
}
