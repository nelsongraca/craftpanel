package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

interface RecoveryCodeRepository {

    fun insert(userId: Uuid, codeHashes: List<String>)

    fun consumeCode(userId: Uuid, codeHash: String): Boolean

    fun countRemaining(userId: Uuid): Int

    fun deleteAll(userId: Uuid)
}