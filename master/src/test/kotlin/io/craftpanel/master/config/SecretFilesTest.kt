package io.craftpanel.master.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

class SecretFilesTest : FunSpec({

    test("returns the plain value when no _FILE env var is set") {
        val result = secretFromFileOrValue("JWT_SECRET", "plain-value", getenv = { null })
        result shouldBe "plain-value"
    }

    test("returns plain value when _FILE is set but blank") {
        val result = secretFromFileOrValue("JWT_SECRET", "plain-value", getenv = { "" })
        result shouldBe "plain-value"
    }

    test("reads and trims file contents when _FILE points to a readable file") {
        val dir = Files.createTempDirectory("secretfiles").toFile()
        try {
            val secretFile = File(dir, "secret").apply { writeText("  s3cr3t-from-file\n") }
            val result = secretFromFileOrValue(
                "JWT_SECRET",
                "plain-value",
                getenv = { name -> if (name == "JWT_SECRET_FILE") secretFile.path else null },
            )
            result shouldBe "s3cr3t-from-file"
        } finally {
            dir.deleteRecursively()
        }
    }

    test("fatal when _FILE is set but the path is not a readable file") {
        val ex = shouldThrow<IllegalStateException> {
            secretFromFileOrValue(
                "JWT_SECRET",
                "plain-value",
                getenv = { name -> if (name == "JWT_SECRET_FILE") "/nonexistent/definitely/not/here" else null },
            )
        }
        ex.message shouldContain "not a readable file"
    }
})
