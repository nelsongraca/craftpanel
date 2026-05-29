package io.craftpanel.master.routes

enum class WsEventType(val event: String) {
    SNAPSHOT("snapshot"),
    NODE_METRICS("node.metrics"),
    NODE_STATUS("node.status"),
    SERVER_METRICS("server.metrics"),
    SERVER_STATUS("server.status"),
    SERVER_PLAYERS("server.players"),
    SERVER_BACKUP_PROGRESS("server.backup.progress"),
    SERVER_BACKUP_COMPLETE("server.backup.complete"),
    ALERT_FIRED("alert.fired"),
    ALERT_RESOLVED("alert.resolved");

    override fun toString(): String = event
}
