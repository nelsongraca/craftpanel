package io.craftpanel.master.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.io.File

/**
 * Architectural guard: the pending-restart marker must only ever be *set* by [ServerSpecSync], which
 * pairs it with the spec re-push. If a config writer sets `restart_pending` directly again, the
 * agent never learns the spec changed and the next restart recreates from the stale spec — the bug
 * this change fixes. `NodeObserver` is allowed because it only *clears* the marker.
 */
class SpecSyncGuardTest :
    FunSpec({

        test("restart_pending is only set by ServerSpecSync") {
            val root = sequenceOf(File("src/main/kotlin"), File("master/src/main/kotlin"))
                .firstOrNull { it.isDirectory }
                ?: error("master source root not found (cwd=${File(".").absolutePath})")

            val offenders = root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name != "ServerSpecSync.kt" }
                .filter { file ->
                    val text = file.readText()
                    text.contains("restartPending = true") || text.contains("[Servers.restartPending] = true")
                }
                .map { it.relativeTo(root).path }
                .toList()

            offenders.shouldBeEmpty()
        }
    })
