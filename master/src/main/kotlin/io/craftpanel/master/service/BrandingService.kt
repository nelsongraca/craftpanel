package io.craftpanel.master.service

import io.craftpanel.master.service.repo.SettingsRepository
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO

class BrandingService(private val settingsRepository: SettingsRepository) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val cacheDir: File by lazy {
        File(System.getProperty("java.io.tmpdir"), "craftpanel-logo-cache").also { it.mkdirs() }
    }

    companion object {

        private val DATA_URI_REGEX = Regex("^data:(image/[a-z+]+);base64,(.+)$", RegexOption.DOT_MATCHES_ALL)

        val DEFAULT_SVG: String =
            """<svg width="110.85132" height="128" viewBox="0 0 29.32941 33.866667" version="1.1" xmlns="http://www.w3.org/2000/svg"><defs/><g transform="translate(-106.13726,115.1769)"><g transform="matrix(0.09921875,0,0,0.09921875,103.86863,-115.17689)"><g transform="translate(-387.9541,9.4069004)" style="fill:#ba6103;fill-opacity:1"><path d="m 641.75911,119.41778 -77.80562,44.92084 v 68.50846 L 706.42252,150.59245 V 75.926432 L 641.75911,38.593099 Z" style="fill:#ba6103;fill-opacity:1;stroke-width:0.166667"/><path d="M 706.42252,162.23047 598.6582,224.44805 v 84.36249 L 706.42252,246.5931 Z" style="fill:#ba6103;fill-opacity:1;stroke-width:0.166667"/><path d="m 553.28682,232.84708 v -68.50846 l -59.32943,-34.25391 v 68.50846 z" style="fill:#ba6103;fill-opacity:1;stroke-width:0.166667"/></g><g transform="translate(-387.9541,9.4069004)" style="fill:#da8315;fill-opacity:1"><path d="M 631.68098,107.44141 631.68033,32.774088 558.62044,-9.4069009 410.81901,75.926432 V 246.5931 l 147.80143,85.33333 29.95898,-17.29688 v -74.66666 l -29.95898,17.29687 -83.13802,-48 v -96 l 83.13802,-47.999995 z" style="fill:#da8315;fill-opacity:1;stroke-width:0.166667"/><path d="m 617.94958,120.84708 -59.32943,-34.253901 -59.32942,34.253901 59.32942,34.25391 z" style="fill:#da8315;fill-opacity:1;stroke-width:0.166667"/><rect style="fill:#da8315;fill-opacity:1;stroke:none;stroke-width:0.183973" width="21.160082" height="16.564816" x="-806.05554" y="-600.64417" transform="matrix(-0.86628723,0.49954623,0,-1,0,0)"/><rect style="fill:#da8315;fill-opacity:1;stroke:none;stroke-width:0.183973" width="21.160082" height="16.564816" x="-804.33728" y="-542.04663" transform="matrix(-0.86628723,0.49954623,0,-1,0,0)"/></g></g></g></svg>"""
        const val DEFAULT_CONTENT_TYPE = "image/svg+xml"
    }

    fun hasCustomLogo(): Boolean {
        val row = settingsRepository.getAll()
            .firstOrNull { it.key == "app_logo" }
        return row?.value?.isNotBlank() == true
    }

    fun getLogoData(): Pair<ByteArray, String> {
        val row = settingsRepository.getAll()
            .firstOrNull { it.key == "app_logo" }
        val raw = row?.value?.takeIf { it.isNotBlank() }
        if (raw != null) {
            val m = DATA_URI_REGEX.matchEntire(raw)
            if (m != null) {
                val bytes = java.util.Base64.getDecoder()
                    .decode(m.groupValues[2])
                return bytes to m.groupValues[1]
            }
        }
        return DEFAULT_SVG.toByteArray(Charsets.UTF_8) to DEFAULT_CONTENT_TYPE
    }

    fun getLogoHash(): String {
        val (bytes, _) = getLogoData()
        return sha256hex(bytes).substring(0, 16)
    }

    fun getCachedIcon(size: Int): ByteArray {
        val (bytes, contentType) = getLogoData()
        val hash = sha256hex(bytes).substring(0, 16)
        val cacheFile = File(cacheDir, "icon-${size}-$hash.png")

        if (cacheFile.exists()) return cacheFile.readBytes()

        if (contentType == "image/svg+xml") {
            return bytes
        }

        val img = ImageIO.read(ByteArrayInputStream(bytes))
        if (img == null) {
            log.warn("Cannot rasterize logo (contentType=$contentType, size=$size); returning raw bytes")
            return bytes
        }

        val scaled = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = scaled.createGraphics()
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            val bg = java.awt.Color(0x27, 0x24, 0x1f)
            g.color = bg
            g.fillRect(0, 0, size, size)
            val bw = minOf(size, img.width * size / img.height)
            val bh = minOf(size, img.height * size / img.width)
            val ox = (size - bw) / 2
            val oy = (size - bh) / 2
            g.drawImage(img, ox, oy, bw, bh, null)
        }
        finally {
            g.dispose()
        }

        val baos = java.io.ByteArrayOutputStream()
        ImageIO.write(scaled, "png", baos)
        val iconBytes = baos.toByteArray()
        cacheFile.writeBytes(iconBytes)
        return iconBytes
    }

    fun invalidateCache() {
        cacheDir.listFiles()
            ?.forEach { it.delete() }
    }

    private fun sha256hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
