package io.craftpanel.common

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class SecretFilesTest :
    FunSpec({

        test("returns null when the _FILE variable is unset") {
            secretFileContents("TOKEN") { null } shouldBe null
        }

        test("returns null when the _FILE variable is blank") {
            secretFileContents("TOKEN") { name -> if (name == "TOKEN_FILE") "  " else null } shouldBe null
        }

        test("returns the trimmed file contents when _FILE is set and readable") {
            val file = Files.createTempFile("secret", ".txt")
            Files.writeString(file, "  s3cr3t\n")

            secretFileContents("TOKEN") { name -> if (name == "TOKEN_FILE") file.toString() else null } shouldBe "s3cr3t"
        }

        test("fails loudly when _FILE points at an unreadable path") {
            shouldThrow<IllegalStateException> {
                secretFileContents("TOKEN") { name -> if (name == "TOKEN_FILE") "/nonexistent/secret" else null }
            }
        }
    })
