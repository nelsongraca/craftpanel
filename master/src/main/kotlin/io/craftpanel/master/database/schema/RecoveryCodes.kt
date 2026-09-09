package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

object RecoveryCodes : Table("recovery_codes") {

    val id = uuid("id").autoGenerate()
    val userId = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)
    val codeHash = varchar("code_hash", 64)
    val used = bool("used").default(false)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, userId)
        index(false, codeHash)
    }
}