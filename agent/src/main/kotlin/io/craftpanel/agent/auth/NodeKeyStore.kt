package io.craftpanel.agent.auth

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

object NodeKeyStore {

    private val log = LoggerFactory.getLogger(NodeKeyStore::class.java)

    fun read(keyFilePath: String): String? {
        val file = File(keyFilePath)
        if (!file.exists()) return null
        return file.readText()
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    fun write(keyFilePath: String, key: String) {
        val file = File(keyFilePath)
        file.parentFile?.mkdirs()
        // Create with restrictive perms before writing so the key is never world-readable.
        runCatching {
            if (!file.exists()) {
                Files.createFile(file.toPath())
            }
            Files.setPosixFilePermissions(
                file.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
        file.writeText(key)
        // Fallback for non-POSIX filesystems: best-effort via Java File API.
        runCatching {
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        }
        log.info("Node key persisted to $keyFilePath")
    }

    fun readDataTokenHash(tokenHashFilePath: String): String? = read(tokenHashFilePath)

    fun writeDataTokenHash(tokenHashFilePath: String, tokenHash: String) {
        write(tokenHashFilePath, tokenHash)
        log.info("Node data-token hash persisted to $tokenHashFilePath")
    }
}
