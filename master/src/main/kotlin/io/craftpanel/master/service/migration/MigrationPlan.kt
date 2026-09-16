package io.craftpanel.master.service.migration

import io.craftpanel.master.service.repo.NodeRow
import io.craftpanel.master.service.repo.ServerView
import kotlin.uuid.Uuid

data class MigrationPlan(
    val migrationId: Uuid,
    val migrationIdStr: String,
    val serverId: Uuid,
    val serverIdStr: String,
    val sourceNodeId: Uuid,
    val sourceNodeIdStr: String,
    val targetNodeId: Uuid,
    val targetNodeIdStr: String,
    val rsyncImage: String,
    val playerWarningMessage: String,
    val containerNamePrefix: String,
    val serverRow: ServerView,
    val targetNodeRow: NodeRow,
    val targetPrivateIp: String
) {

    var rsyncPort: Int = 0
    var rsyncPassword: String = ""
    var sourceStopped: Boolean = false

    /** True while the source is under a `no_restart` guard for the duration of the live sync. */
    var sourceGuarded: Boolean = false
    var assignedPort: Int = 0
    var freshServerView: ServerView? = null
}
