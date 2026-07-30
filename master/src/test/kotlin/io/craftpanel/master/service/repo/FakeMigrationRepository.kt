package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

class FakeMigrationRepository(private val state: FakeRepositories) : MigrationRepository {

    override fun findActiveMigration(serverId: Uuid): MigrationRow? =
        state.migrations.values.firstOrNull { it.serverId == serverId && it.status in listOf("PENDING", "RUNNING", "SYNCING", "CUTTING_OVER") }
            ?.toRow()

    override fun listMigrations(serverId: Uuid): List<MigrationRow> = state.migrations.values.filter { it.serverId == serverId }
        .map { it.toRow() }

    override fun findMigrationById(id: Uuid): MigrationRow? = state.migrations[id]?.toRow()

    override fun listMigrationSteps(migrationId: Uuid): List<MigrationStepRow> = state.steps[migrationId]?.map { it.toRow() }
        ?.toList() ?: emptyList()

    private fun FakeServerRepository.MutableMigration.toRow() = MigrationRow(id, serverId, sourceNodeId, targetNodeId, status, createdAt, completedAt)
    private fun FakeServerRepository.MutableMigrationStep.toRow() = MigrationStepRow(id, migrationId, stepNumber, description, status, startedAt, completedAt, errorMessage)
}