package io.craftpanel.agent.auth

import org.slf4j.LoggerFactory
import java.io.File

object NodeKeyStore {
    private val log = LoggerFactory.getLogger(NodeKeyStore::class.java)

    fun read(keyFilePath: String): String? {
        val file = File(keyFilePath)
        if (!file.exists()) return null
        return file.readText().trim().takeIf { it.isNotEmpty() }
    }

    fun write(keyFilePath: String, key: String) {
        val file = File(keyFilePath)
        file.parentFile?.mkdirs()
        file.writeText(key)
        file.setReadable(false, false)
        file.setReadable(true, true)   // owner-only read
        file.setWritable(false, false)
        file.setWritable(true, true)   // owner-only write
        log.info("Node key persisted to $keyFilePath")
    }
}
