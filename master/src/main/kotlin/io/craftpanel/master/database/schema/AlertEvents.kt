package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

object AlertEvents : UuidTable("alert_events") {

    val thresholdId = reference("threshold_id", AlertThresholds, onDelete = ReferenceOption.CASCADE)
    val firedAt = datetime("fired_at").defaultExpression(CurrentDateTime)
    val resolvedAt = datetime("resolved_at").nullable()
    val message = text("message")
}
