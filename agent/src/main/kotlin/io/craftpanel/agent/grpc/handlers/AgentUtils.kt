package io.craftpanel.agent.grpc.handlers

import com.google.protobuf.timestamp
import io.craftpanel.agent.grpc.AgentOutbound
import io.craftpanel.common.ServerPaths
import io.craftpanel.proto.ServerStatusUpdate
import org.slf4j.Logger
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.SecureRandom
import java.time.Instant

/**
 * Process-wide `serverId -> dataDirName` overrides, fed by master's state sync. The data directory
 * name defaults to the server id and may be overridden by an admin. Master re-pushes the full
 * mapping on every reconnect ([io.craftpanel.proto.RebuildSymlinksCommand]) and inside each
 * desired-state spec, so nothing is persisted here.
 *
 * Only the override map lives here; the naming rule itself is [ServerPaths]. Not every server has
 * an entry — absence means "use the server id".
 */
internal object ServerDataDirs {

    private val log = org.slf4j.LoggerFactory.getLogger(ServerDataDirs::class.java)

    @Volatile
    private var byServer: Map<String, String> = emptyMap()

    fun nameFor(serverId: String): String? = byServer[serverId]

    fun put(serverId: String, dataDirName: String) {
        val clean = dataDirName.trim()
        if (ServerPaths.isValidDataDirName(clean)) {
            byServer = byServer + (serverId to clean)
        } else {
            if (clean.isNotEmpty()) log.warn("Ignoring invalid data directory name '{}' for server {}", clean, serverId)
            byServer = byServer - serverId
        }
    }

    /** Atomically replaces the whole mapping from a master snapshot. */
    fun replaceAll(entries: Map<String, String>) {
        val byName = entries.mapValues { it.value.trim() }
        // Blank = "no override" (the common case) and is not an error; only a non-blank name that
        // fails validation is worth warning about.
        byName.filterValues { it.isNotEmpty() && !ServerPaths.isValidDataDirName(it) }
            .forEach { (serverId, name) -> log.warn("Ignoring invalid data directory name '{}' for server {}", name, serverId) }
        byServer = byName.filterValues { ServerPaths.isValidDataDirName(it) }
    }

    fun remove(serverId: String) {
        byServer = byServer - serverId
    }

    /** Test seam — the mapping is otherwise owned by master's pushes. */
    fun clear() {
        byServer = emptyMap()
    }
}

/**
 * Root data directory for a given server, shared by [FileHandler] and [ContainerHandler].
 * Consults [ServerDataDirs] for the admin override, falling back to the server id.
 *
 * Fail-closed: refuses any path that resolves outside `<dataBasePath>/servers`. [ServerPaths]
 * already guarantees a single safe segment, so this is defence-in-depth at the filesystem
 * trust boundary — the composed path feeds writes, deletes, and container bind mounts.
 */
internal fun serverDataRoot(dataBasePath: String, serverId: String): Path {
    val name = ServerPaths.dataDirName(serverId, ServerDataDirs.nameFor(serverId))
    val serversRoot = Paths.get(dataBasePath, ServerPaths.SERVERS).normalize()
    val resolved = Paths.get(dataBasePath, ServerPaths.SERVERS, name).normalize()
    check(resolved.parent == serversRoot) { "Refusing data path outside $serversRoot: $resolved" }
    return resolved
}

/** Recursively deletes a file or directory tree, shared by [FileHandler] and [ContainerHandler]. */
internal fun deleteRecursively(path: Path) {
    Files.walkFileTree(
        path,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        }
    )
}

internal fun nowTimestamp() = timestamp {
    val now = Instant.now()
    seconds = now.epochSecond
    nanos = now.nano
}

internal fun generateRsyncPassword(): String {
    // Alphanumeric charset only — intentional security invariant.
    // The password is echoed unquoted into the rsyncd secrets file and interpolated
    // into a sh -c script; shell metacharacters would cause injection. Do not widen.
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    val random = SecureRandom()
    return (1..32).map { chars[random.nextInt(chars.length)] }
        .joinToString("")
}

internal suspend fun withStatus(out: AgentOutbound, serverId: String, successStatus: ServerStatusUpdate.ServerStatus, log: Logger, logContext: String, block: suspend () -> Unit) {
    runCatching { block() }
        .onSuccess { if (serverId.isNotEmpty()) out.serverStatus(serverId, successStatus) }
        .onFailure { e ->
            log.error(logContext, e)
            if (serverId.isNotEmpty()) out.serverStatus(serverId, ServerStatusUpdate.ServerStatus.UNHEALTHY)
        }
}
