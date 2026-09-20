package io.craftpanel.master

import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.database.SchemaMigrator
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
            SchemaMigrator.migrate(
                Users, RefreshTokens, RecoveryCodes, Groups, GroupPermissions, UserGroupAssignments,
                ServerNetworks, Nodes, Servers, ServerEnvVars, NodeMetrics, PortRegistry, ServerMigrations,
                MigrationStepLog, Backups, AlertThresholds, AlertEvents, ContainerMetrics, ServerMods,
                SystemSettings, ServerJobs, ProxyBackends, ServerExtraPorts, TrustedDevices
            )
            seedSystemGroups()
        }
        initialized = true
    }

    fun reset() {
        transaction {
            // Referential integrity stays ON so tests exercise the real FK cascade/SET_NULL behaviour.
            // Deletion is children-first purely so the explicit clear does not depend on cascade.
            listOf(
                AlertEvents, AlertThresholds, Backups, ServerMods, ProxyBackends,
                MigrationStepLog, ServerMigrations, PortRegistry, ContainerMetrics,
                NodeMetrics, ServerEnvVars, ServerJobs, ServerExtraPorts, Servers, Nodes, ServerNetworks,
                SystemSettings, TrustedDevices, RefreshTokens, RecoveryCodes, UserGroupAssignments, Groups, Users
            ).forEach { it.deleteAll() }
            seedSystemGroups()
        }
        // The resolver caches grants for 60s; clear it so one test's users/groups never leak
        // into another's permission checks.
        PermissionResolver.invalidateAll()
    }
}
