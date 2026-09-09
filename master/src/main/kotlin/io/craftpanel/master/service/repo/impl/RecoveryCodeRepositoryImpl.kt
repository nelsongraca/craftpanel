package io.craftpanel.master.service.repo.impl

import io.craftpanel.master.database.schema.RecoveryCodes
import io.craftpanel.master.database.schema.Users
import io.craftpanel.master.service.repo.RecoveryCodeRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class RecoveryCodeRepositoryImpl : RecoveryCodeRepository {

    override fun insert(userId: Uuid, codeHashes: List<String>) {
        transaction {
            codeHashes.forEach { hash ->
                RecoveryCodes.insert {
                    it[RecoveryCodes.userId] = EntityID(userId, Users)
                    it[codeHash] = hash
                }
            }
        }
    }

    override fun consumeCode(userId: Uuid, codeHash: String): Boolean = transaction {
        val rowId = RecoveryCodes.selectAll()
            .where {
                (RecoveryCodes.userId eq userId) and
                    (RecoveryCodes.codeHash eq codeHash) and
                    (RecoveryCodes.used eq false)
            }
            .firstOrNull()
            ?.get(RecoveryCodes.id)
            ?: return@transaction false

        RecoveryCodes.update({ RecoveryCodes.id eq rowId }) { it[used] = true }
        true
    }

    override fun countRemaining(userId: Uuid): Int = transaction {
        RecoveryCodes.selectAll()
            .where { (RecoveryCodes.userId eq userId) and (RecoveryCodes.used eq false) }
            .count()
            .toInt()
    }

    override fun deleteAll(userId: Uuid) {
        transaction {
            RecoveryCodes.deleteWhere { RecoveryCodes.userId eq userId }
        }
    }
}