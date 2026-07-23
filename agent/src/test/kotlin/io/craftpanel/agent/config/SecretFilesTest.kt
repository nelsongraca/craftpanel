package io.craftpanel.agent.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class SecretFilesTest :
    FunSpec({
        test("returns default when neither env nor _FILE is set") {
            secretFromFileOrEnv("TOKEN", "default-value") { null } shouldBe "default-value"
        }

        test("returns env value when set and _FILE is unset") {
            secretFromFileOrEnv("TOKEN", "default-value") { name -> if (name == "TOKEN") "from-env" else null } shouldBe "from-env"
        }

        test("reads and trims file contents when _FILE is set") {
            val dir = Files.createTempDirectory("secret-files-test")
            try {
                val secretFile = dir.resolve("token").toFile()
                secretFile.writeText("  from-file  \n")
                secretFromFileOrEnv("TOKEN", "default-value") { name ->
                    if (name == "TOKEN_FILE") secretFile.absolutePath else null
                } shouldBe "from-file"
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        test("_FILE takes priority over plain env value") {
            val dir = Files.createTempDirectory("secret-files-test")
            try {
                val secretFile = dir.resolve("token").toFile()
                secretFile.writeText("from-file")
                secretFromFileOrEnv("TOKEN", "default-value") { name ->
                    when (name) {
                        "TOKEN_FILE" -> secretFile.absolutePath
                        "TOKEN" -> "from-env"
                        else -> null
                    }
                } shouldBe "from-file"
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        test("throws when _FILE points to a missing file") {
            val ex = runCatching {
                secretFromFileOrEnv("TOKEN", "default-value") { name -> if (name == "TOKEN_FILE") "/nonexistent/path" else null }
            }.exceptionOrNull()
            (ex is IllegalStateException) shouldBe true
        }

        test("blank _FILE value falls back to plain env") {
            secretFromFileOrEnv("TOKEN", "default-value") { name ->
                when (name) {
                    "TOKEN_FILE" -> ""
                    "TOKEN" -> "from-env"
                    else -> null
                }
            } shouldBe "from-env"
        }
    })
