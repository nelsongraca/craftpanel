package io.craftpanel.master

import io.craftpanel.master.database.migrations.seedSystemGroups
import io.craftpanel.master.database.schema.*
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
                ServerNetworks, Nodes, Servers, ServerEnvVars, NodeMetrics, PortRegistry, ServerMigrations,
                MigrationStepLog, Backups, AlertThresholds, AlertEvents, ContainerMetrics, ServerMods,
                SystemSettings, ServerJobs, ProxyBackends
            )
            seedSystemGroups()
        }
        initialized = true
    }

    fun reset() {
        transaction {
            exec("SET REFERENTIAL_INTEGRITY FALSE")
            listOf(
                AlertEvents, AlertThresholds, Backups, ServerMods, ProxyBackends,
                MigrationStepLog, ServerMigrations, PortRegistry, ContainerMetrics,
                NodeMetrics, ServerEnvVars, ServerJobs, Servers, Nodes, ServerNetworks,
                SystemSettings, RefreshTokens, UserGroupAssignments, Groups, Users
            ).forEach { it.deleteAll() }
            exec("SET REFERENTIAL_INTEGRITY TRUE")
            seedSystemGroups()
        }
    }
}
