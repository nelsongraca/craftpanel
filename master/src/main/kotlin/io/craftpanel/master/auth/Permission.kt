package io.craftpanel.master.auth

import kotlinx.serialization.Serializable

enum class Permission(val node: String) {
    SYSTEM_SETTINGS("system.settings"),
    SYSTEM_USERS("system.users"),
    SYSTEM_NODES("system.nodes"),
    SYSTEM_GROUPS("system.groups"),
    SYSTEM_ALERTS("system.alerts"),

    SERVER_CREATE("server.create"),
    SERVER_DELETE("server.delete"),
    SERVER_START("server.start"),
    SERVER_STOP("server.stop"),
    SERVER_FORCE_STOP("server.force_stop"),
    SERVER_RESTART("server.restart"),
    SERVER_CONFIGURE("server.configure"),
    SERVER_RESOURCES("server.resources"),
    SERVER_FILES("server.files"),

    // Super-admin-only: override the server's data directory name. Deliberately NOT seeded into
    // any system group except Super Admin (which holds `*`).
    SERVER_DIR_OVERRIDE("server.dir_override"),
    SERVER_MODS("server.mods"),
    SERVER_CONSOLE("server.console"),
    SERVER_EXPORT("server.export"),
    SERVER_BACKUP("server.backup"),
    SERVER_MIGRATE("server.migrate"),
    SERVER_EXPIRES("server.expires"),
    SERVER_DISABLE("server.disable"),
    SERVER_VIEW("server.view"),

    NETWORK_VIEW("network.view"),
    NETWORK_CREATE("network.create"),
    NETWORK_CONFIGURE("network.configure"),
    NETWORK_DELETE("network.delete");

    override fun toString(): String = node
}

@Serializable
enum class ScopeType {

    GLOBAL,
    SERVER,
    NETWORK,
    NODE
}
