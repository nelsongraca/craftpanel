package io.craftpanel.common

/**
 * The single owner of CraftPanel's on-disk server-data convention.
 *
 * Master and the agent both depend on this module so the layout physically cannot drift between
 * the two processes — the same rationale as [ContainerNames] for Docker names. The data directory
 * for a server is `servers/<name>`, where `<name>` defaults to the server id and may be overridden
 * by an admin (`server.dir_override`) for special cases.
 *
 * All values are plain path strings (no `java.nio.file`), so this module stays dependency-free and
 * the caller decides how to turn them into `Path`s.
 */
object ServerPaths {

    /** Directory under the data root that holds every server's data directory. */
    const val SERVERS = "servers"

    /** Symlink-overlay root mapping human-readable server names to canonical data directories. */
    const val SERVERS_BY_NAME = "servers-by-name"

    /** Symlink-overlay root mapping human-readable server names to canonical backup files. */
    const val BACKUPS_BY_SERVER = "backups-by-server"

    /**
     * Effective data directory name for a server: the admin override when it is a valid single
     * path segment, otherwise the server id. Validation here (not just at the API boundary) is
     * load-bearing — the name is interpolated into filesystem paths by the agent, so a value that
     * escaped validation (bad row, compromised master) must never be allowed to traverse.
     */
    fun dataDirName(serverId: String, override: String?): String = override?.takeIf { isValidDataDirName(it) } ?: serverId

    /** `servers/<effectiveName>` — relative to the data base path. */
    fun relativeDataDir(serverId: String, override: String?): String = "$SERVERS/${dataDirName(serverId, override)}"

    /** `<basePath>/servers/<effectiveName>` — a full path string. */
    fun dataDir(basePath: String, serverId: String, override: String?): String = "$basePath/$SERVERS/${dataDirName(serverId, override)}"

    /** Default `servers-by-name` overlay root under [basePath]. */
    fun serversByNameRoot(basePath: String): String = "$basePath/$SERVERS_BY_NAME"

    /** Default `backups-by-server` overlay root under [basePath]. */
    fun backupsByServerRoot(basePath: String): String = "$basePath/$BACKUPS_BY_SERVER"

    private val DATA_DIR_NAME = Regex("[a-zA-Z][a-zA-Z0-9_-]*")

    /**
     * Whether [name] is a valid data directory name. Must match `[a-zA-Z][a-zA-Z0-9_-]*` and be at
     * most 100 chars — a single, non-hidden path segment that cannot traverse. Starting with a
     * letter rules out `.`/`..`, and the character class rules out separators. Uniqueness per node
     * is enforced by master, not here.
     */
    fun isValidDataDirName(name: String): Boolean = name.length <= 100 && DATA_DIR_NAME.matches(name)
}
