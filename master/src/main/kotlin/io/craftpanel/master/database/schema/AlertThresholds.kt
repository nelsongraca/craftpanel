package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

object AlertThresholds : Table("alert_thresholds") {

    val id = uuid("id").autoGenerate()
    val scopeType = varchar("scope_type", 6)          // NODE | SERVER
    val scopeId = uuid("scope_id")
    val metric = varchar("metric", 64)             // cpu_percent | ram_percent | disk_percent | server_health
    val thresholdValue = double("threshold_value").nullable()
    val thresholdState = varchar("threshold_state", 32).nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}
