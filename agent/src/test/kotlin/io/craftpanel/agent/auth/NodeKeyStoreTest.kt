package io.craftpanel.agent.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class NodeKeyStoreTest : FunSpec({
    var tempDir: Path = Files.createTempDirectory("nodekey-test")

    beforeTest {
        tempDir = Files.createTempDirectory("nodekey-test")
    }

    afterTest {
        tempDir.toFile().deleteRecursively()
    }

    test("read returns null when file does not exist") {
        val result = NodeKeyStore.read(tempDir.resolve("missing.key").toString())
        result shouldBe null
    }

    test("read returns null when file is empty") {
        val keyFile = tempDir.resolve("empty.key").toFile()
        keyFile.writeText("")
        NodeKeyStore.read(keyFile.absolutePath) shouldBe null
    }

    test("read returns null when file contains only whitespace") {
        val keyFile = tempDir.resolve("blank.key").toFile()
        keyFile.writeText("   \n  ")
        NodeKeyStore.read(keyFile.absolutePath) shouldBe null
    }

    test("read returns trimmed content") {
        val keyFile = tempDir.resolve("node.key").toFile()
        keyFile.writeText("  abc123secret  \n")
        NodeKeyStore.read(keyFile.absolutePath) shouldBe "abc123secret"
    }

    test("write then read roundtrips key correctly") {
        val keyPath = tempDir.resolve("node.key").toString()
        NodeKeyStore.write(keyPath, "my-256-bit-node-key")
        NodeKeyStore.read(keyPath) shouldBe "my-256-bit-node-key"
    }

    test("write creates parent directories") {
        val keyPath = tempDir.resolve("nested/sub/node.key").toString()
        NodeKeyStore.write(keyPath, "some-key")
        File(keyPath).exists() shouldBe true
        NodeKeyStore.read(keyPath) shouldBe "some-key"
    }

    test("write overwrites existing file") {
        val keyPath = tempDir.resolve("node.key").toString()
        NodeKeyStore.write(keyPath, "first-key")
        NodeKeyStore.write(keyPath, "second-key")
        NodeKeyStore.read(keyPath) shouldBe "second-key"
    }
})
