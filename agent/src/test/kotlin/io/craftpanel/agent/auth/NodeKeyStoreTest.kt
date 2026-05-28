package io.craftpanel.agent.auth

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class NodeKeyStoreTest {

    private lateinit var tempDir: Path

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("nodekey-test")
    }

    @AfterTest
    fun teardown() {
        tempDir.toFile()
            .deleteRecursively()
    }

    @Test
    fun `read returns null when file does not exist`() {
        val result = NodeKeyStore.read(
            tempDir.resolve("missing.key")
                .toString()
        )
        assertNull(result)
    }

    @Test
    fun `read returns null when file is empty`() {
        val keyFile = tempDir.resolve("empty.key")
            .toFile()
        keyFile.writeText("")
        assertNull(NodeKeyStore.read(keyFile.absolutePath))
    }

    @Test
    fun `read returns null when file contains only whitespace`() {
        val keyFile = tempDir.resolve("blank.key")
            .toFile()
        keyFile.writeText("   \n  ")
        assertNull(NodeKeyStore.read(keyFile.absolutePath))
    }

    @Test
    fun `read returns trimmed content`() {
        val keyFile = tempDir.resolve("node.key")
            .toFile()
        keyFile.writeText("  abc123secret  \n")
        assertEquals("abc123secret", NodeKeyStore.read(keyFile.absolutePath))
    }

    @Test
    fun `write then read roundtrips key correctly`() {
        val keyPath = tempDir.resolve("node.key")
            .toString()
        NodeKeyStore.write(keyPath, "my-256-bit-node-key")
        assertEquals("my-256-bit-node-key", NodeKeyStore.read(keyPath))
    }

    @Test
    fun `write creates parent directories`() {
        val keyPath = tempDir.resolve("nested/sub/node.key")
            .toString()
        NodeKeyStore.write(keyPath, "some-key")
        assertTrue(File(keyPath).exists())
        assertEquals("some-key", NodeKeyStore.read(keyPath))
    }

    @Test
    fun `write overwrites existing file`() {
        val keyPath = tempDir.resolve("node.key")
            .toString()
        NodeKeyStore.write(keyPath, "first-key")
        NodeKeyStore.write(keyPath, "second-key")
        assertEquals("second-key", NodeKeyStore.read(keyPath))
    }
}
