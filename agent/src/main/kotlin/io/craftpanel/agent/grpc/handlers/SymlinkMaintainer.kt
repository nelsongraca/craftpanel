package io.craftpanel.agent.grpc.handlers

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Maintains human-readable symlink overlays alongside the UUID-keyed canonical
 * storage (`servers/<uuid>`, `backups/<backupId>.tar.gz`). Canonical paths are
 * never modified by this object — it only ever creates or removes symlinks
 * that point at them.
 */
object SymlinkMaintainer {

    private val log = LoggerFactory.getLogger(SymlinkMaintainer::class.java)

    /**
     * Creates (or verifies) a `<serversByNameRoot>/<name>` symlink to [canonicalPath].
     * On a real collision (path exists and points elsewhere), falls back to
     * `<name>-<uuid8>` using the last path segment of [canonicalPath] (the server UUID)
     * as the suffix source.
     */
    fun createServerNameSymlink(serversByNameRoot: String, name: String, canonicalPath: Path) {
        val root = Paths.get(serversByNameRoot)
        Files.createDirectories(root)
        val relativeTarget = root.relativize(canonicalPath)
        createOrReplaceSymlink(root, name, canonicalPath, relativeTarget)
    }

    fun removeServerNameSymlink(serversByNameRoot: String, name: String) {
        removeIfSymlink(Paths.get(serversByNameRoot, name))
    }

    /**
     * Creates a `<backupsByServerRoot>/<name>/<timestamp>.tar.gz` symlink to
     * [canonicalBackupFile]. [timestamp] must already be pre-formatted
     * (e.g. `2026-07-18_14-30-00`) — this function does no date formatting.
     */
    fun createBackupSymlink(backupsByServerRoot: String, name: String, timestamp: String, canonicalBackupFile: Path) {
        val serverDir = Paths.get(backupsByServerRoot, name)
        Files.createDirectories(serverDir)
        val linkName = "$timestamp.tar.gz"
        val relativeTarget = serverDir.relativize(canonicalBackupFile)
        createOrReplaceSymlink(serverDir, linkName, canonicalBackupFile, relativeTarget)
    }

    fun removeBackupSymlink(backupsByServerRoot: String, name: String, timestamp: String) {
        removeIfSymlink(Paths.get(backupsByServerRoot, name, "$timestamp.tar.gz"))
    }

    private fun createOrReplaceSymlink(parentDir: Path, linkName: String, canonicalTarget: Path, relativeTarget: Path) {
        val link = parentDir.resolve(linkName)
        if (Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            val existingTarget = runCatching { Files.readSymbolicLink(link) }.getOrNull()
            val resolvedExisting = existingTarget?.let { parentDir.resolve(it).normalize() }
            val resolvedCanonical = canonicalTarget.normalize()
            if (resolvedExisting == resolvedCanonical) {
                return // already correct, idempotent no-op
            }
            // Real collision: fall back to a uuid8-suffixed name using the canonical
            // path's last segment (the server/backup UUID) as the disambiguator.
            val suffix = canonicalTarget.fileName.toString().take(8)
            val suffixedName = "$linkName-$suffix".let {
                if (linkName.endsWith(".tar.gz")) "${linkName.removeSuffix(".tar.gz")}-$suffix.tar.gz" else it
            }
            val suffixedLink = parentDir.resolve(suffixedName)
            if (!Files.exists(suffixedLink, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                Files.createSymbolicLink(suffixedLink, parentDir.relativize(canonicalTarget))
                log.info("Symlink collision at {} — created {} instead", link, suffixedLink)
            }
            return
        }
        Files.createSymbolicLink(link, relativeTarget)
    }

    private fun removeIfSymlink(path: Path) {
        if (Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
            Files.delete(path)
        }
    }
}
