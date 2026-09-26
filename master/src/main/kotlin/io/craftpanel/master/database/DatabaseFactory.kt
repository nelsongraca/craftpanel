package io.craftpanel.master.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.craftpanel.master.config.DatabaseConfig
import io.craftpanel.master.database.migrations.seedSystemGroups
import io.craftpanel.master.database.schema.*
import io.craftpanel.master.domain.DesiredStatus
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
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
                ServerStatusEvents,
                SystemSettings,
                ServerJobs,
                ServerExtraPorts,
                TrustedDevices
            )
            widenCustomHostnameColumn()
            widenServerStatusColumn()
            seedSystemGroups()
            backfillDesiredStatus()
            migrateBackendForwardingEnvToColumn()
        }
    }

    /**
     * `custom_hostname` grew from a single DNS name (253) to a comma-separated list
     * ([Servers.CUSTOM_HOSTNAME_MAX_LENGTH]). Exposed's migration utils add/drop columns but never
     * alter varchar lengths, so the widening is explicit and idempotent. Postgres-only: tests build
     * the schema fresh from the definition.
     */
    private fun JdbcTransaction.widenCustomHostnameColumn() {
        val currentLength = exec(
            "SELECT character_maximum_length FROM information_schema.columns " +
                "WHERE table_name = 'servers' AND column_name = 'custom_hostname'"
        ) { rs -> if (rs.next()) rs.getInt(1) else null } ?: return
        if (currentLength < Servers.CUSTOM_HOSTNAME_MAX_LENGTH) {
            exec("ALTER TABLE servers ALTER COLUMN custom_hostname TYPE varchar(${Servers.CUSTOM_HOSTNAME_MAX_LENGTH})")
        }
    }

    /**
     * `status` grew from 10 to [Servers.STATUS_MAX_LENGTH] so the agent-reported `CRASH_LOOPED`
     * (12 chars) can be persisted — at 10 every crash-loop report failed the insert. Exposed's
     * migration utils never alter varchar lengths, so the widening is explicit and idempotent.
     * Postgres-only: tests build the schema fresh from the definition.
     */
    private fun JdbcTransaction.widenServerStatusColumn() {
        val currentLength = exec(
            "SELECT character_maximum_length FROM information_schema.columns " +
                "WHERE table_name = 'servers' AND column_name = 'status'"
        ) { rs -> if (rs.next()) rs.getInt(1) else null } ?: return
        if (currentLength < Servers.STATUS_MAX_LENGTH) {
            exec("ALTER TABLE servers ALTER COLUMN status TYPE varchar(${Servers.STATUS_MAX_LENGTH})")
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

    /**
     * `PATCH_DEFINITIONS` used to be persisted as a user env var on forwarding backends; it is now a
     * master-owned `servers.forwarding_patch_file` column injected at container-build time. Copy any
     * existing value across and drop the env rows so the UI no longer shows it and a config save
     * cannot wipe it. Idempotent.
     */
    private fun Transaction.migrateBackendForwardingEnvToColumn() {
        ServerEnvVars.selectAll()
            .where { ServerEnvVars.key eq "PATCH_DEFINITIONS" }
            .forEach { row ->
                Servers.update({ Servers.id eq row[ServerEnvVars.serverId].value }) {
                    it[forwardingPatchFile] = row[ServerEnvVars.value]
                }
            }
        ServerEnvVars.deleteWhere { ServerEnvVars.key eq "PATCH_DEFINITIONS" }
    }
}
