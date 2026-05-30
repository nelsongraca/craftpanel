package io.craftpanel.master.util

import io.craftpanel.master.service.UnprocessableException

private val SAFE_PATH_REGEX = Regex("^/[A-Za-z0-9._/-]+\$")

fun assertSafeDataPath(path: String) {
    if (!SAFE_PATH_REGEX.matches(path))
        throw UnprocessableException("Unsafe dataPath: $path")
    if (path.contains(".."))
        throw UnprocessableException("Path traversal not allowed in dataPath: $path")
    if (path.endsWith("/"))
        throw UnprocessableException("dataPath must not end with /: $path")
}
