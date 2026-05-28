package io.craftpanel.master

import io.craftpanel.master.database.migrations.seedSystemGroups
import io.craftpanel.master.database.schema.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

object TestDatabase {

    private var initialized = false

    fun initIfNeeded() {
        if (initialized) return
        Database.connect("jdbc:h2:mem:craftpanel_test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction {
            SchemaUtils.create(
                Users, RefreshTokens, Groups, GroupPermissions, UserGroupAssignments,
                ServerNetworks, Nodes, Servers, ServerEnvVars, NodeMetrics, PortRegistry, ServerMigrations, Backups,
                AlertThresholds, AlertEvents, ContainerMetrics, ServerMods,
                SystemSettings,
            )
            seedSystemGroups()
        }
        initialized = true
    }

    fun reset() {
        transaction {
            AlertEvents.deleteAll()
            AlertThresholds.deleteAll()
            Backups.deleteAll()
            ServerMods.deleteAll()
            ServerMigrations.deleteAll()
            PortRegistry.deleteAll()
            ContainerMetrics.deleteAll()
            NodeMetrics.deleteAll()
            ServerEnvVars.deleteAll()
            Servers.deleteAll()
            Nodes.deleteAll()
            ServerNetworks.deleteAll()
            SystemSettings.deleteAll()
            RefreshTokens.deleteAll()
            UserGroupAssignments.deleteAll()
            Users.deleteAll()
            val nonSystemGroupIds = Groups.selectAll()
                .where { Groups.isSystem eq false }
                .map { it[Groups.id] }
            if (nonSystemGroupIds.isNotEmpty()) {
                GroupPermissions.deleteWhere { GroupPermissions.groupId inList nonSystemGroupIds }
                Groups.deleteWhere { Groups.isSystem eq false }
            }
        }
    }
}
