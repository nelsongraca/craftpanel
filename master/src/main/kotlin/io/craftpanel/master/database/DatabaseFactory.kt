package io.craftpanel.master.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.craftpanel.master.config.DatabaseConfig
import io.craftpanel.master.database.migrations.seedSystemGroups
import io.craftpanel.master.database.schema.*
import io.craftpanel.master.domain.DesiredStatus
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

object DatabaseFactory {

    fun init(config: DatabaseConfig) {
        val dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.url
                username = config.username
                password = config.password
                maximumPoolSize = config.maximumPoolSize
                driverClassName = "org.postgresql.Driver"
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_REPEATABLE_READ"
                validate()
            }
        )

        Database.connect(dataSource)

        transaction {
            SchemaMigrator.migrate(
                Users,
                RefreshTokens,
                RecoveryCodes,
                Groups,
                GroupPermissions,
                UserGroupAssignments,
                Nodes,
                NodeMetrics,
                ServerNetworks,
                Servers,
                ServerEnvVars,
                PortRegistry,
                ProxyBackends,
                ServerMods,
                Backups,
                ServerMigrations,
                MigrationStepLog,
                AlertThresholds,
                AlertEvents,
                ContainerMetrics,
                SystemSettings,
                ServerJobs,
                ServerExtraPorts,
                TrustedDevices
            )
            seedSystemGroups()
            backfillDesiredStatus()
        }
    }

    /**
     * One-time backfill for the new `desired_status` column: existing running servers (agent-reported
     * HEALTHY/STARTING) already have intent RUNNING; everything else desired STOPPED. Idempotent —
     * only touches rows still unset.
     */
    private fun Transaction.backfillDesiredStatus() {
        Servers.update({ Servers.desiredStatus.isNull() and (Servers.status inList listOf("HEALTHY", "STARTING")) }) {
            it[desiredStatus] = DesiredStatus.RUNNING.toDb()
        }
        Servers.update({ Servers.desiredStatus.isNull() }) {
            it[desiredStatus] = DesiredStatus.STOPPED.toDb()
        }
    }
}
