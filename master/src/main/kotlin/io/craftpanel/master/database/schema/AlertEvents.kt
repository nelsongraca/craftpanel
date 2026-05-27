package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

object AlertEvents : Table("alert_events") {
    val id          = uuid("id").autoGenerate()
    val thresholdId = uuid("threshold_id").references(AlertThresholds.id)
    val firedAt     = datetime("fired_at").defaultExpression(CurrentDateTime)
    val resolvedAt  = datetime("resolved_at").nullable()
    val message     = text("message")

    override val primaryKey = PrimaryKey(id)
}
