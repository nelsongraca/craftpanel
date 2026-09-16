package io.craftpanel.common

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ServerPathsTest :
    FunSpec({

        test("dataDirName defaults to the server id") {
            ServerPaths.dataDirName("srv-1", null) shouldBe "srv-1"
            ServerPaths.dataDirName("srv-1", "") shouldBe "srv-1"
            ServerPaths.dataDirName("srv-1", "   ") shouldBe "srv-1"
        }

        test("dataDirName honours a non-blank override") {
            ServerPaths.dataDirName("srv-1", "survival") shouldBe "survival"
        }

        test("dataDirName falls back to the id for a traversal attempt") {
            ServerPaths.dataDirName("srv-1", "/etc") shouldBe "srv-1"
            ServerPaths.dataDirName("srv-1", "../../etc") shouldBe "srv-1"
            ServerPaths.dataDirName("srv-1", "..") shouldBe "srv-1"
            ServerPaths.dataDirName("srv-1", "a/../../etc") shouldBe "srv-1"
        }

        test("dataDir cannot escape the servers root via the override") {
            ServerPaths.dataDir("/data", "srv-1", "../../etc") shouldBe "/data/servers/srv-1"
            ServerPaths.dataDir("/data", "srv-1", "/etc") shouldBe "/data/servers/srv-1"
        }

        test("dataDir composes base + servers + effective name") {
            ServerPaths.dataDir("/data", "srv-1", null) shouldBe "/data/servers/srv-1"
            ServerPaths.dataDir("/data", "srv-1", "survival") shouldBe "/data/servers/survival"
        }

        test("relativeDataDir omits the base path") {
            ServerPaths.relativeDataDir("srv-1", null) shouldBe "servers/srv-1"
            ServerPaths.relativeDataDir("srv-1", "survival") shouldBe "servers/survival"
        }

        test("overlay roots derive from the base path") {
            ServerPaths.serversByNameRoot("/data") shouldBe "/data/servers-by-name"
            ServerPaths.backupsByServerRoot("/data") shouldBe "/data/backups-by-server"
        }

        test("valid names are single non-hidden segments") {
            ServerPaths.isValidDataDirName("survival") shouldBe true
            ServerPaths.isValidDataDirName("my-server_2") shouldBe true
            ServerPaths.isValidDataDirName("a") shouldBe true
            ServerPaths.isValidDataDirName("A".repeat(100)) shouldBe true
        }

        test("invalid names are rejected") {
            ServerPaths.isValidDataDirName("") shouldBe false
            ServerPaths.isValidDataDirName("   ") shouldBe false
            ServerPaths.isValidDataDirName("A".repeat(101)) shouldBe false
            ServerPaths.isValidDataDirName(".") shouldBe false
            ServerPaths.isValidDataDirName("..") shouldBe false
            ServerPaths.isValidDataDirName(".hidden") shouldBe false
            ServerPaths.isValidDataDirName("a/b") shouldBe false
            ServerPaths.isValidDataDirName("a\\b") shouldBe false
            ServerPaths.isValidDataDirName("a\u0000b") shouldBe false
            ServerPaths.isValidDataDirName("a\nb") shouldBe false
            // must start with a letter
            ServerPaths.isValidDataDirName("9abc") shouldBe false
            ServerPaths.isValidDataDirName("_abc") shouldBe false
            ServerPaths.isValidDataDirName("-abc") shouldBe false
            ServerPaths.isValidDataDirName("abc!") shouldBe false
            ServerPaths.isValidDataDirName("abc def") shouldBe false
        }
    })
