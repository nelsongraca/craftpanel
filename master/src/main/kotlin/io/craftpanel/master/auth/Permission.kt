package io.craftpanel.master.auth

enum class Permission(val node: String) {
    SYSTEM_SETTINGS("system.settings"),
    SYSTEM_USERS("system.users"),
    SYSTEM_NODES("system.nodes"),

    SERVER_CREATE("server.create"),
    SERVER_DELETE("server.delete"),
    SERVER_START("server.start"),
    SERVER_STOP("server.stop"),
    SERVER_RESTART("server.restart"),
    SERVER_CONFIGURE("server.configure"),
    SERVER_RESOURCES("server.resources"),
    SERVER_FILES("server.files"),
    SERVER_MODS("server.mods"),
    SERVER_CONSOLE("server.console"),
    SERVER_EXPORT("server.export"),
    SERVER_BACKUP("server.backup"),
    SERVER_MIGRATE("server.migrate"),
    SERVER_VIEW("server.view");

    override fun toString(): String = node
}
