package io.craftpanel.master.service.repo

import io.craftpanel.master.domain.MigrationStatus
import io.craftpanel.master.domain.MigrationStepStatus
import kotlin.uuid.Uuid

class FakeMigrationRepository(private val state: FakeRepositories) : MigrationRepository {

    override fun findActiveMigration(serverId: Uuid): MigrationRow? =
        state.migrations.values.firstOrNull { it.serverId == serverId && it.status in listOf("PENDING", "RUNNING", "SYNCING", "CUTTING_OVER") }
            ?.toRow()

    override fun listMigrations(serverId: Uuid): List<MigrationRow> = state.migrations.values.filter { it.serverId == serverId }
        .map { it.toRow() }

    override fun findMigrationById(id: Uuid): MigrationRow? = state.migrations[id]?.toRow()

    override fun createMigration(serverId: Uuid, sourceNodeId: Uuid, targetNodeId: Uuid): MigrationRow {
        val id = Uuid.random()
        val m = FakeServerRepository.MutableMigration(id, serverId, sourceNodeId, targetNodeId)
        state.migrations[id] = m
        return m.toRow()
    }

    override fun updateMigrationStatus(id: Uuid, status: MigrationStatus, completedAt: kotlin.time.Instant?) {
        state.migrations[id]?.let {
            it.status = status.name
            if (completedAt != null) it.completedAt = completedAt.toString()
        }
    }

    override fun failMigrationsForNode(nodeId: Uuid) {
        val active = listOf(MigrationStatus.PENDING.name, MigrationStatus.SYNCING.name, MigrationStatus.CUTTING_OVER.name)
        state.migrations.values.filter { (it.sourceNodeId == nodeId || it.targetNodeId == nodeId) && it.status in active }
            .forEach { it.status = MigrationStatus.FAILED.name }
    }

    override fun failAllStuckMigrations() {
        val stuck = listOf(MigrationStatus.PENDING.name, MigrationStatus.SYNCING.name, MigrationStatus.CUTTING_OVER.name, MigrationStatus.RUNNING.name)
        state.migrations.values.filter { it.status in stuck }
            .forEach {
                it.status = MigrationStatus.FAILED.name
                it.completedAt = "now"
            }
    }

    override fun updateNodeId(id: Uuid, nodeId: Uuid) {
        state.servers[id]?.nodeId = nodeId
    }

    override fun updateMigrationHostPort(id: Uuid, hostPort: Int) {
        state.servers[id]?.hostPort = hostPort
    }

    override fun listMigrationSteps(migrationId: Uuid): List<MigrationStepRow> = state.steps[migrationId]?.map { it.toRow() }
        ?.toList() ?: emptyList()

    override fun createMigrationStep(migrationId: Uuid, stepNumber: Int, description: String): MigrationStepRow {
        val id = Uuid.random()
        val s = FakeServerRepository.MutableMigrationStep(id, migrationId, stepNumber, description)
        state.steps.getOrPut(migrationId) { mutableListOf() }
            .add(s)
        return s.toRow()
    }

    override fun updateMigrationStepStatus(id: Uuid, status: MigrationStepStatus, startedAt: kotlin.time.Instant?, completedAt: kotlin.time.Instant?, errorMessage: String?) {
        val s = state.steps.values.flatten()
            .firstOrNull { it.id == id } ?: return
        s.status = status.name
        if (startedAt != null) s.startedAt = startedAt.toString()
        if (completedAt != null) s.completedAt = completedAt.toString()
        if (errorMessage != null) s.errorMessage = errorMessage
    }

    override fun deleteMigrationStepsForServer(serverId: Uuid) {
        val migrationIds = state.migrations.values.filter { it.serverId == serverId }
            .map { it.id }
        migrationIds.forEach { state.steps.remove(it) }
    }

    override fun deleteMigrationsForServer(serverId: Uuid) {
        state.migrations.values.removeAll { it.serverId == serverId }
    }

    private fun FakeServerRepository.MutableMigration.toRow() = MigrationRow(id, serverId, sourceNodeId, targetNodeId, status, createdAt, completedAt)
    private fun FakeServerRepository.MutableMigrationStep.toRow() = MigrationStepRow(id, migrationId, stepNumber, description, status, startedAt, completedAt, errorMessage)
}
