package io.craftpanel.common

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ServerNamesTest :
    FunSpec({

        context("isValid") {
            test("accepts a lowercase slug") {
                ServerNames.isValid("survival-1") shouldBe true
                ServerNames.isValid("a") shouldBe true
                ServerNames.isValid("123") shouldBe true
            }
            test("accepts a 63-character name") {
                ServerNames.isValid("a".repeat(63)) shouldBe true
            }
            test("rejects an empty name") {
                ServerNames.isValid("") shouldBe false
            }
            test("rejects a 64-character name") {
                ServerNames.isValid("a".repeat(64)) shouldBe false
            }
            test("rejects uppercase") {
                ServerNames.isValid("Survival") shouldBe false
            }
            test("rejects underscores, dots, spaces and non-ascii") {
                ServerNames.isValid("sur_vival") shouldBe false
                ServerNames.isValid("sur.vival") shouldBe false
                ServerNames.isValid("sur vival") shouldBe false
                ServerNames.isValid("survivalé") shouldBe false
            }
            test("rejects a leading hyphen") {
                ServerNames.isValid("-survival") shouldBe false
            }
        }

        context("collidesWithPrefix") {
            test("rejects the bare prefix and prefix-prefixed names") {
                ServerNames.collidesWithPrefix("craftpanel", "craftpanel") shouldBe true
                ServerNames.collidesWithPrefix("craftpanel-router", "craftpanel") shouldBe true
                ServerNames.collidesWithPrefix("craftpanel-abc", "craftpanel") shouldBe true
            }
            test("accepts unrelated names") {
                ServerNames.collidesWithPrefix("survival", "craftpanel") shouldBe false
                ServerNames.collidesWithPrefix("my-craftpanel", "craftpanel") shouldBe false
            }
            test("honours a configured prefix and normalises trailing separators") {
                ServerNames.collidesWithPrefix("cp-router", "cp") shouldBe true
                ServerNames.collidesWithPrefix("cp-router", "cp-") shouldBe true
                ServerNames.collidesWithPrefix("craftpanel-router", "cp") shouldBe false
            }
        }
    })
