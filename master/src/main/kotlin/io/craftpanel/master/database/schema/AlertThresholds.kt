package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

object AlertThresholds : Table("alert_thresholds") {
    val id = uuid("id").autoGenerate()
    val nodeId = uuid("node_id").references(Nodes.id).nullable()
    val serverId = uuid("server_id").references(Servers.id).nullable()
    val metric = varchar("metric", 50)          // cpu_percent, ram_percent, disk_percent, player_count...
    val operator = varchar("operator", 2)       // GT | LT | EQ
    val thresholdValue = double("threshold_value")
    val enabled = bool("enabled").default(true)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}
