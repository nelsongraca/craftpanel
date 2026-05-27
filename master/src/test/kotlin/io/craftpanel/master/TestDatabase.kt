package io.craftpanel.master

import io.craftpanel.master.database.migrations.seedSystemGroups
import io.craftpanel.master.database.schema.AlertEvents
import io.craftpanel.master.database.schema.AlertThresholds
import io.craftpanel.master.database.schema.Backups
import io.craftpanel.master.database.schema.ContainerMetrics
import io.craftpanel.master.database.schema.GroupPermissions
import io.craftpanel.master.database.schema.Groups
import io.craftpanel.master.database.schema.NodeMetrics
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.PortRegistry
import io.craftpanel.master.database.schema.RefreshTokens
import io.craftpanel.master.database.schema.ServerEnvVars
import io.craftpanel.master.database.schema.ServerMigrations
import io.craftpanel.master.database.schema.ServerMods
import io.craftpanel.master.database.schema.ServerNetworks
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.database.schema.SystemSettings
import io.craftpanel.master.database.schema.UserGroupAssignments
import io.craftpanel.master.database.schema.Users
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
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
