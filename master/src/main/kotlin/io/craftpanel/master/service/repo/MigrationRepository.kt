package io.craftpanel.master.service.repo

import io.craftpanel.master.domain.MigrationStatus
import io.craftpanel.master.domain.MigrationStepStatus
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
    fun createMigration(serverId: Uuid, sourceNodeId: Uuid, targetNodeId: Uuid): MigrationRow
    fun updateMigrationStatus(id: Uuid, status: MigrationStatus, completedAt: kotlin.time.Instant?)
    fun failMigrationsForNode(nodeId: Uuid)
    fun failAllStuckMigrations()
    fun updateNodeId(id: Uuid, nodeId: Uuid)
    fun updateMigrationHostPort(id: Uuid, hostPort: Int)
    fun listMigrationSteps(migrationId: Uuid): List<MigrationStepRow>
    fun createMigrationStep(migrationId: Uuid, stepNumber: Int, description: String): MigrationStepRow
    fun updateMigrationStepStatus(id: Uuid, status: MigrationStepStatus, startedAt: kotlin.time.Instant?, completedAt: kotlin.time.Instant?, errorMessage: String?)
    fun deleteMigrationStepsForServer(serverId: Uuid)
    fun deleteMigrationsForServer(serverId: Uuid)
}
