package io.craftpanel.master

import io.craftpanel.master.database.migrations.seedSystemGroups
import io.craftpanel.master.database.schema.GroupPermissions
import io.craftpanel.master.database.schema.Groups
import io.craftpanel.master.database.schema.NodeMetrics
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.RefreshTokens
import io.craftpanel.master.database.schema.ServerNetworks
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.database.schema.UserGroupAssignments
import io.craftpanel.master.database.schema.Users
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

object TestDatabase {
    private var initialized = false

    fun initIfNeeded() {
        if (initialized) return
        Database.connect("jdbc:h2:mem:craftpanel_test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction {
            SchemaUtils.create(
                Users, RefreshTokens, Groups, GroupPermissions, UserGroupAssignments,
                ServerNetworks, Nodes, Servers, NodeMetrics,
            )
            seedSystemGroups()
        }
        initialized = true
    }

    fun reset() {
        transaction {
            NodeMetrics.deleteAll()
            Servers.deleteAll()
            Nodes.deleteAll()
            ServerNetworks.deleteAll()
            RefreshTokens.deleteAll()
            UserGroupAssignments.deleteAll()
            Users.deleteAll()
        }
    }
}
