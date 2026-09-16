package io.craftpanel.agent.grpc.handlers

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ServerDataDirsTest :
    FunSpec({

        beforeTest { ServerDataDirs.clear() }
        afterTest { ServerDataDirs.clear() }

        test("serverDataRoot defaults to the server id") {
            serverDataRoot("/data", "srv-1").toString() shouldBe "/data/servers/srv-1"
        }

        test("serverDataRoot honours an override") {
            ServerDataDirs.put("srv-1", "survival")
            serverDataRoot("/data", "srv-1").toString() shouldBe "/data/servers/survival"
        }

        test("a blank override reverts to the server id") {
            ServerDataDirs.put("srv-1", "survival")
            ServerDataDirs.put("srv-1", "  ")
            ServerDataDirs.nameFor("srv-1") shouldBe null
            serverDataRoot("/data", "srv-1").toString() shouldBe "/data/servers/srv-1"
        }

        test("put trims the override") {
            ServerDataDirs.put("srv-1", "  survival  ")
            ServerDataDirs.nameFor("srv-1") shouldBe "survival"
        }

        test("replaceAll atomically swaps the whole mapping") {
            ServerDataDirs.put("old", "old-dir")
            ServerDataDirs.replaceAll(mapOf("srv-1" to "survival", "srv-2" to ""))
            ServerDataDirs.nameFor("old") shouldBe null
            ServerDataDirs.nameFor("srv-1") shouldBe "survival"
            ServerDataDirs.nameFor("srv-2") shouldBe null
        }

        test("remove drops an entry") {
            ServerDataDirs.put("srv-1", "survival")
            ServerDataDirs.remove("srv-1")
            ServerDataDirs.nameFor("srv-1") shouldBe null
        }

        test("an invalid override is rejected and the id path is used") {
            ServerDataDirs.put("srv-1", "../../etc")
            ServerDataDirs.nameFor("srv-1") shouldBe null
            serverDataRoot("/data", "srv-1").toString() shouldBe "/data/servers/srv-1"
        }

        test("replaceAll drops invalid entries") {
            ServerDataDirs.replaceAll(mapOf("a" to "../../etc", "b" to "/etc", "c" to "ok"))
            ServerDataDirs.nameFor("a") shouldBe null
            ServerDataDirs.nameFor("b") shouldBe null
            ServerDataDirs.nameFor("c") shouldBe "ok"
        }
    })
