package io.craftpanel.master

import io.craftpanel.master.config.ImagesConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ImagesConfigTest : FunSpec({
    val defaultImages = ImagesConfig(
        minecraftImage = "itzg/minecraft-server",
        proxyImage = "itzg/mc-proxy",
    )

    test("appends tag to untagged minecraft image") {
        defaultImages.deriveImage("PAPER", "latest") shouldBe "itzg/minecraft-server:latest"
    }

    test("appends custom tag to untagged minecraft image") {
        defaultImages.deriveImage("PAPER", "2024.6.0") shouldBe "itzg/minecraft-server:2024.6.0"
    }

    test("appends tag to untagged proxy image for VELOCITY") {
        defaultImages.deriveImage("VELOCITY", "latest") shouldBe "itzg/mc-proxy:latest"
    }

    test("appends tag to untagged proxy image for BUNGEECORD") {
        defaultImages.deriveImage("BUNGEECORD", "latest") shouldBe "itzg/mc-proxy:latest"
    }

    test("appends tag to untagged proxy image for WATERFALL") {
        defaultImages.deriveImage("WATERFALL", "latest") shouldBe "itzg/mc-proxy:latest"
    }

    test("override minecraft image with tag is used as-is") {
        val images = ImagesConfig("craftpanel-fake-server:test", "itzg/mc-proxy")
        images.deriveImage("PAPER", "latest") shouldBe "craftpanel-fake-server:test"
    }

    test("override proxy image with tag is used as-is") {
        val images = ImagesConfig("itzg/minecraft-server", "craftpanel-fake-proxy:test")
        images.deriveImage("VELOCITY", "latest") shouldBe "craftpanel-fake-proxy:test"
    }

    test("override image without tag gets tag appended") {
        val images = ImagesConfig("craftpanel-fake-server", "itzg/mc-proxy")
        images.deriveImage("PAPER", "latest") shouldBe "craftpanel-fake-server:latest"
    }

    test("proxy server types resolve data container path to /server") {
        defaultImages.dataContainerPath("VELOCITY") shouldBe "/server"
        defaultImages.dataContainerPath("BUNGEECORD") shouldBe "/server"
        defaultImages.dataContainerPath("WATERFALL") shouldBe "/server"
    }

    test("non-proxy server types resolve data container path to /data") {
        defaultImages.dataContainerPath("VANILLA") shouldBe "/data"
        defaultImages.dataContainerPath("PAPER") shouldBe "/data"
        defaultImages.dataContainerPath("FORGE") shouldBe "/data"
    }
})
