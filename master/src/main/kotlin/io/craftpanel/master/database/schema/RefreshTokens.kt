package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

object RefreshTokens : Table("refresh_tokens") {

    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(Users.id)
    val tokenHash = varchar("token_hash", 64)  // SHA-256 hex, 64 chars
    val expiresAt = datetime("expires_at")
    val revoked = bool("revoked").default(false)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, tokenHash)
        index(false, userId)
    }
}
