package io.craftpanel.agent.grpc.handlers

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readSymbolicLink

class SymlinkMaintainerTest :
    FunSpec({

        lateinit var dataBasePath: Path
        lateinit var serversByNameRoot: Path

        beforeTest {
            dataBasePath = Files.createTempDirectory("craftpanel-data")
            serversByNameRoot = Files.createTempDirectory("craftpanel-by-name")
        }

        afterTest {
            dataBasePath.toFile().deleteRecursively()
            serversByNameRoot.toFile().deleteRecursively()
        }

        test("createServerNameSymlink creates a relative symlink to the canonical server dir") {
            val serverId = "11111111-1111-1111-1111-111111111111"
            val canonicalRoot = serverDataRoot(dataBasePath.toString(), serverId)
            canonicalRoot.createDirectories()

            SymlinkMaintainer.createServerNameSymlink(
                serversByNameRoot = serversByNameRoot.toString(),
                name = "my-survival-world",
                canonicalPath = canonicalRoot
            )

            val link = serversByNameRoot.resolve("my-survival-world")
            link.exists() shouldBe true
            link.isSymbolicLink() shouldBe true
            val target = link.readSymbolicLink()
            target.toString() shouldStartWith ".."
        }

        test("createServerNameSymlink appends uuid8 suffix on real collision") {
            val serverIdA = "11111111-1111-1111-1111-111111111111"
            val serverIdB = "22222222-2222-2222-2222-222222222222"
            val rootA = serverDataRoot(dataBasePath.toString(), serverIdA).also { it.createDirectories() }
            val rootB = serverDataRoot(dataBasePath.toString(), serverIdB).also { it.createDirectories() }

            SymlinkMaintainer.createServerNameSymlink(serversByNameRoot.toString(), "duplicate-name", rootA)
            SymlinkMaintainer.createServerNameSymlink(serversByNameRoot.toString(), "duplicate-name", rootB)

            serversByNameRoot.resolve("duplicate-name").exists() shouldBe true
            serversByNameRoot.resolve("duplicate-name-22222222").exists() shouldBe true
        }

        test("createServerNameSymlink is idempotent when called twice for the same server") {
            val serverId = "33333333-3333-3333-3333-333333333333"
            val root = serverDataRoot(dataBasePath.toString(), serverId).also { it.createDirectories() }

            SymlinkMaintainer.createServerNameSymlink(serversByNameRoot.toString(), "stable-name", root)
            SymlinkMaintainer.createServerNameSymlink(serversByNameRoot.toString(), "stable-name", root)

            // Second call must not create a -33333333 suffixed duplicate — same target, no collision.
            serversByNameRoot.resolve("stable-name").exists() shouldBe true
            serversByNameRoot.resolve("stable-name-33333333").exists() shouldBe false
        }

        test("removeServerNameSymlink deletes the symlink") {
            val serverId = "44444444-4444-4444-4444-444444444444"
            val root = serverDataRoot(dataBasePath.toString(), serverId).also { it.createDirectories() }
            SymlinkMaintainer.createServerNameSymlink(serversByNameRoot.toString(), "removable", root)
            serversByNameRoot.resolve("removable").exists() shouldBe true

            SymlinkMaintainer.removeServerNameSymlink(serversByNameRoot.toString(), "removable")

            serversByNameRoot.resolve("removable").exists() shouldBe false
        }

        test("removeServerNameSymlink on a missing symlink is a no-op, does not throw") {
            SymlinkMaintainer.removeServerNameSymlink(serversByNameRoot.toString(), "never-existed")
        }
    })
