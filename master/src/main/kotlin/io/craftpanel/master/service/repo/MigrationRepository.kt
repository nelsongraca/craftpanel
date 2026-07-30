package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

data class MigrationRow(val id: Uuid, val serverId: Uuid, val sourceNodeId: Uuid, val targetNodeId: Uuid, val status: String, val createdAt: String, val completedAt: String?)

data class MigrationStepRow(
    val id: Uuid,
    val migrationId: Uuid,
    val stepNumber: Int,
    val description: String,
    val status: String,
    val startedAt: String?,
    val completedAt: String?,
    val errorMessage: String?
)

interface MigrationRepository {

    fun findActiveMigration(serverId: Uuid): MigrationRow?
    fun listMigrations(serverId: Uuid): List<MigrationRow>
    fun findMigrationById(id: Uuid): MigrationRow?
    fun listMigrationSteps(migrationId: Uuid): List<MigrationStepRow>
}
