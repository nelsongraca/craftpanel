package io.craftpanel.agent.config

import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("io.craftpanel.agent.config.SecretFiles")

/**
 * Resolves a secret with the Docker/Kubernetes `_FILE` convention.
 *
 * Precedence: `<envName>_FILE` (file contents) > plain env value > default.
 * A set-but-unreadable `_FILE` path is fatal — failing loudly beats silently
 * falling back to a weaker source for a credential.
 */
fun secretFromFileOrEnv(envName: String, default: String): String {
    val filePath = System.getenv("${envName}_FILE")
        ?.takeIf { it.isNotBlank() }
    if (filePath != null) {
        val file = File(filePath)
        check(file.isFile && file.canRead()) { "${envName}_FILE points to '$filePath' which is not a readable file" }
        log.info("Loaded secret $envName from ${envName}_FILE ($filePath)")
        return file.readText()
            .trim()
    }
    return System.getenv(envName) ?: default
}
