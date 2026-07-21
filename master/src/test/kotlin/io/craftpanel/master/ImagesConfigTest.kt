package io.craftpanel.master

import io.craftpanel.master.config.ImagesConfig
import io.craftpanel.master.domain.ServerType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ImagesConfigTest :
    FunSpec({
        val defaultImages = ImagesConfig(
            minecraftImage = "itzg/minecraft-server",
            proxyImage = "itzg/mc-proxy"
        )

        test("appends tag to untagged minecraft image") {
            defaultImages.deriveImage(ServerType.PAPER, "latest") shouldBe "itzg/minecraft-server:latest"
        }

        test("appends custom tag to untagged minecraft image") {
            defaultImages.deriveImage(ServerType.PAPER, "2024.6.0") shouldBe "itzg/minecraft-server:2024.6.0"
        }

        test("appends tag to untagged proxy image for VELOCITY") {
            defaultImages.deriveImage(ServerType.VELOCITY, "latest") shouldBe "itzg/mc-proxy:latest"
        }

        test("appends tag to untagged proxy image for BUNGEECORD") {
            defaultImages.deriveImage(ServerType.BUNGEECORD, "latest") shouldBe "itzg/mc-proxy:latest"
        }

        test("appends tag to untagged proxy image for WATERFALL") {
            defaultImages.deriveImage(ServerType.WATERFALL, "latest") shouldBe "itzg/mc-proxy:latest"
        }

        test("override minecraft image with tag is used as-is") {
            val images = ImagesConfig("craftpanel-fake-server:test", "itzg/mc-proxy")
            images.deriveImage(ServerType.PAPER, "latest") shouldBe "craftpanel-fake-server:test"
        }

        test("override proxy image with tag is used as-is") {
            val images = ImagesConfig("itzg/minecraft-server", "craftpanel-fake-proxy:test")
            images.deriveImage(ServerType.VELOCITY, "latest") shouldBe "craftpanel-fake-proxy:test"
        }

        test("override image without tag gets tag appended") {
            val images = ImagesConfig("craftpanel-fake-server", "itzg/mc-proxy")
            images.deriveImage(ServerType.PAPER, "latest") shouldBe "craftpanel-fake-server:latest"
        }

        test("proxy server types resolve data container path to /server") {
            defaultImages.dataContainerPath(ServerType.VELOCITY) shouldBe "/server"
            defaultImages.dataContainerPath(ServerType.BUNGEECORD) shouldBe "/server"
            defaultImages.dataContainerPath(ServerType.WATERFALL) shouldBe "/server"
        }

        test("non-proxy server types resolve data container path to /data") {
            defaultImages.dataContainerPath(ServerType.VANILLA) shouldBe "/data"
            defaultImages.dataContainerPath(ServerType.PAPER) shouldBe "/data"
            defaultImages.dataContainerPath(ServerType.FORGE) shouldBe "/data"
        }

        test("proxy server types resolve internal listen port to 25577") {
            defaultImages.internalListenPort(ServerType.VELOCITY) shouldBe 25577
            defaultImages.internalListenPort(ServerType.BUNGEECORD) shouldBe 25577
            defaultImages.internalListenPort(ServerType.WATERFALL) shouldBe 25577
        }

        test("non-proxy server types resolve internal listen port to 25565") {
            defaultImages.internalListenPort(ServerType.VANILLA) shouldBe 25565
            defaultImages.internalListenPort(ServerType.PAPER) shouldBe 25565
            defaultImages.internalListenPort(ServerType.FORGE) shouldBe 25565
        }
    })
