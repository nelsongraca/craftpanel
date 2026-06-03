package io.craftpanel.master

import io.craftpanel.master.config.ImagesConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class ImagesConfigTest {

    private val defaultImages = ImagesConfig(
        minecraftImage = "itzg/minecraft-server",
        proxyImage = "itzg/mc-proxy",
    )

    @Test
    fun `appends tag to untagged minecraft image`() {
        assertEquals("itzg/minecraft-server:latest", defaultImages.deriveImage("PAPER", "latest"))
    }

    @Test
    fun `appends custom tag to untagged minecraft image`() {
        assertEquals("itzg/minecraft-server:2024.6.0", defaultImages.deriveImage("PAPER", "2024.6.0"))
    }

    @Test
    fun `appends tag to untagged proxy image for VELOCITY`() {
        assertEquals("itzg/mc-proxy:latest", defaultImages.deriveImage("VELOCITY", "latest"))
    }

    @Test
    fun `appends tag to untagged proxy image for BUNGEECORD`() {
        assertEquals("itzg/mc-proxy:latest", defaultImages.deriveImage("BUNGEECORD", "latest"))
    }

    @Test
    fun `appends tag to untagged proxy image for WATERFALL`() {
        assertEquals("itzg/mc-proxy:latest", defaultImages.deriveImage("WATERFALL", "latest"))
    }

    @Test
    fun `override minecraft image with tag is used as-is`() {
        val images = ImagesConfig("craftpanel-fake-server:test", "itzg/mc-proxy")
        assertEquals("craftpanel-fake-server:test", images.deriveImage("PAPER", "latest"))
    }

    @Test
    fun `override proxy image with tag is used as-is`() {
        val images = ImagesConfig("itzg/minecraft-server", "craftpanel-fake-proxy:test")
        assertEquals("craftpanel-fake-proxy:test", images.deriveImage("VELOCITY", "latest"))
    }

    @Test
    fun `override image without tag gets tag appended`() {
        val images = ImagesConfig("craftpanel-fake-server", "itzg/mc-proxy")
        assertEquals("craftpanel-fake-server:latest", images.deriveImage("PAPER", "latest"))
    }
}
