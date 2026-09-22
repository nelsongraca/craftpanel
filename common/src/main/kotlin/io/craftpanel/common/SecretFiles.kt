package io.craftpanel.common

import java.io.File

/**
 * Reads a secret using the Docker/Kubernetes `<envName>_FILE` convention. Returns the file's trimmed
 * contents when `<envName>_FILE` is set and readable, or null when it is not set.
 *
 * A set-but-unreadable `_FILE` path is fatal — failing loudly beats silently falling back to a weaker
 * source for a credential. Each caller keeps its own precedence for the non-file fallback (the agent
 * uses an env value then a default; master uses an already-resolved config value).
 */
fun secretFileContents(envName: String, getenv: (String) -> String? = System::getenv): String? {
    val filePath = getenv("${envName}_FILE")?.takeIf { it.isNotBlank() } ?: return null
    val file = File(filePath)
    check(file.isFile && file.canRead()) { "${envName}_FILE points to '$filePath' which is not a readable file" }
    return file.readText().trim()
}
