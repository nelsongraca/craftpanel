package io.craftpanel.agent.runtime

import io.craftpanel.agent.config.RuntimeSettingsStore
import io.craftpanel.agent.docker.FakeContainerManager
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

class ImageRefresherTest :
    FunSpec({

        fun store(): RuntimeSettingsStore = RuntimeSettingsStore(
            Files.createTempDirectory("img-refresher").resolve("runtime-settings.json").toFile()
        )

        test("pre-pulls every distinct managed image plus the router image") {
            val cm = FakeContainerManager()
            val refresher = ImageRefresher(
                containerManager = cm,
                managedImages = { setOf("itzg/minecraft-server:latest", "itzg/mc-proxy:latest", "itzg/minecraft-server:latest") },
                routerImage = { "itzg/mc-router:latest" },
                settingsStore = store()
            )

            runBlocking { refresher.refreshAll() }

            cm.calls.filter { it.startsWith("refresh:") } shouldContainExactlyInAnyOrder listOf(
                "refresh:itzg/minecraft-server:latest",
                "refresh:itzg/mc-proxy:latest",
                "refresh:itzg/mc-router:latest"
            )
        }

        test("skips a blank router image") {
            val cm = FakeContainerManager()
            val refresher = ImageRefresher(
                containerManager = cm,
                managedImages = { setOf("itzg/minecraft-server:latest") },
                routerImage = { "" },
                settingsStore = store()
            )

            runBlocking { refresher.refreshAll() }

            cm.calls.filter { it.startsWith("refresh:") } shouldContainExactlyInAnyOrder listOf(
                "refresh:itzg/minecraft-server:latest"
            )
        }
    })
