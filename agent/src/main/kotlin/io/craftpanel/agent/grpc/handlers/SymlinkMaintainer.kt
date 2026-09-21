package io.craftpanel.agent.grpc.handlers

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

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
     * A pre-existing symlink at that name is treated as a stale overlay entry and replaced — names
     * are unique, so it can only ever be this server's previous target or a leftover. Only when the
     * name is occupied by a real file/directory does it fall back to `<name>-<lastSegment8>`.
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
        if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            Files.createSymbolicLink(link, relativeTarget)
            return
        }

        if (Files.isSymbolicLink(link)) {
            val existingTarget = runCatching { Files.readSymbolicLink(link) }.getOrNull()
            val resolvedExisting = existingTarget?.let { parentDir.resolve(it).normalize() }
            if (resolvedExisting == canonicalTarget.normalize()) {
                return // already correct, idempotent no-op
            }
            // The overlay is agent-owned and names are unique, so a symlink at an overlay name that
            // points elsewhere is always stale (a changed data-dir override, a rename, or a leftover
            // from a removed server) — replace it rather than folding a suffixed alias next to it.
            replaceSymlink(parentDir, link, canonicalTarget)
            log.info("Replaced stale symlink {} -> {}", link, canonicalTarget)
            return
        }

        // Genuine collision: the overlay name is occupied by a real entry we do not own. Leave it
        // untouched and disambiguate with the canonical target's last segment (name/uuid/backup id).
        val suffix = canonicalTarget.fileName.toString().take(8)
        val suffixedName = "$linkName-$suffix".let {
            if (linkName.endsWith(".tar.gz")) "${linkName.removeSuffix(".tar.gz")}-$suffix.tar.gz" else it
        }
        val suffixedLink = parentDir.resolve(suffixedName)
        if (!Files.exists(suffixedLink, LinkOption.NOFOLLOW_LINKS)) {
            Files.createSymbolicLink(suffixedLink, parentDir.relativize(canonicalTarget))
            log.info("Overlay name {} is occupied by a real entry — created {} instead", link, suffixedLink)
        }
    }

    /** Atomically repoints [link] at [canonicalTarget], replacing whatever symlink is there now. */
    private fun replaceSymlink(parentDir: Path, link: Path, canonicalTarget: Path) {
        val tmp = parentDir.resolve(".${link.fileName}.tmp-${System.nanoTime()}")
        Files.createSymbolicLink(tmp, parentDir.relativize(canonicalTarget))
        try {
            Files.move(tmp, link, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.deleteIfExists(link)
            Files.move(tmp, link, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Files.deleteIfExists(tmp)
            throw e
        }
    }

    private fun removeIfSymlink(path: Path) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
            Files.delete(path)
        }
    }

    /**
     * Deletes every agent-owned overlay symlink under [root] (recursively) that no longer matches
     * [expected], a map from the symlink's own path to the canonical target it should point at.
     * Real (non-symlink) entries are left untouched; directories left empty by pruning are removed.
     * Shared by the servers-by-name and backups-by-server reconnect self-heal paths.
     */
    fun pruneStaleSymlinks(root: Path, expected: Map<Path, Path>) {
        if (!Files.isDirectory(root)) return
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (Files.isSymbolicLink(file)) {
                        val want = expected[file]?.normalize()
                        val actual = runCatching { file.parent.resolve(Files.readSymbolicLink(file)).normalize() }.getOrNull()
                        if (want == null || actual != want) {
                            runCatching { Files.delete(file) }
                                .onFailure { log.warn("Rebuild: failed to prune stale symlink $file", it) }
                        }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    if (dir != root && runCatching { Files.list(dir).use { it.findAny().isEmpty } }.getOrDefault(false)) {
                        runCatching { Files.delete(dir) }
                            .onFailure { log.warn("Rebuild: failed to prune empty directory $dir", it) }
                    }
                    return FileVisitResult.CONTINUE
                }
            }
        )
    }
}
