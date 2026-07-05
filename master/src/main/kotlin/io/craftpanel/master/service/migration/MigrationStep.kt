package io.craftpanel.master.service.migration

sealed class StepResult {
    data object Success : StepResult()
    data class Failure(val error: String) : StepResult()
}

interface MigrationStep {

    val stepNumber: Int
    val description: String
    suspend fun execute(plan: MigrationPlan, coord: MigrationCoordinator): StepResult
}
