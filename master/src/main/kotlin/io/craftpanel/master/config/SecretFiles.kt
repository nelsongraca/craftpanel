package io.craftpanel.master.config

import java.io.File
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("io.craftpanel.master.config.SecretFiles")

/**
 * Resolves a secret with the Docker/Kubernetes `_FILE` convention.
 *
 * Precedence: `<envName>_FILE` (file contents) > plain config/env value > default.
 * When `<envName>_FILE` is set, the file at that path is read and its trimmed
 * contents returned, letting secrets come from mounted files instead of the
 * environment. A set-but-unreadable `_FILE` path is fatal — failing loudly beats
 * silently falling back to a weaker source for a credential.
 */
fun secretFromFileOrValue(
    envName: String,
    value: String,
    getenv: (String) -> String? = System::getenv,
): String {
    val filePath = getenv("${envName}_FILE")?.takeIf { it.isNotBlank() } ?: return value
    val file = File(filePath)
    check(file.isFile && file.canRead()) { "${envName}_FILE points to '$filePath' which is not a readable file" }
    log.info("Loaded secret $envName from ${envName}_FILE ($filePath)")
    return file.readText().trim()
}
