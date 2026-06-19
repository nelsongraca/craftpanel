package io.craftpanel.master

import io.craftpanel.master.service.ServerRestartManager
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.uuid.Uuid

class ServerRestartManagerTest : FunSpec({

    test("allows up to maxAttempts then caps") {
        val mgr = ServerRestartManager(maxAttempts = 5, windowSeconds = 600)
        val id = Uuid.random()
        // first 5 crashes are allowed
        repeat(5) { mgr.recordCrashAndShouldRestart(id) shouldBe true }
        // 6th exceeds the cap
        mgr.recordCrashAndShouldRestart(id) shouldBe false
        mgr.recordCrashAndShouldRestart(id) shouldBe false
    }

    test("reset clears the counter so restarts are allowed again") {
        val mgr = ServerRestartManager(maxAttempts = 3, windowSeconds = 600)
        val id = Uuid.random()
        repeat(3) { mgr.recordCrashAndShouldRestart(id) shouldBe true }
        mgr.recordCrashAndShouldRestart(id) shouldBe false
        mgr.reset(id) // server reached HEALTHY
        repeat(3) { mgr.recordCrashAndShouldRestart(id) shouldBe true }
    }

    test("counters are independent per server") {
        val mgr = ServerRestartManager(maxAttempts = 1, windowSeconds = 600)
        val a = Uuid.random()
        val b = Uuid.random()
        mgr.recordCrashAndShouldRestart(a) shouldBe true
        mgr.recordCrashAndShouldRestart(a) shouldBe false
        // b unaffected by a's cap
        mgr.recordCrashAndShouldRestart(b) shouldBe true
    }

    test("maxAttempts of 0 disables restart entirely") {
        val mgr = ServerRestartManager(maxAttempts = 0, windowSeconds = 600)
        mgr.recordCrashAndShouldRestart(Uuid.random()) shouldBe false
    }
})
