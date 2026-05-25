package io.craftpanel.master.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.craftpanel.master.config.DatabaseConfig
import io.craftpanel.master.database.migrations.seedSystemGroups
import io.craftpanel.master.database.schema.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

object DatabaseFactory {

    fun init(config: DatabaseConfig) {
        val dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = config.url
            username = config.username
            password = config.password
            maximumPoolSize = config.maximumPoolSize
            driverClassName = "org.postgresql.Driver"
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        })

        Database.connect(dataSource)

        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                Users,
                RefreshTokens,
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
                SystemSettings,
            )
            seedSystemGroups()
        }
    }
}
