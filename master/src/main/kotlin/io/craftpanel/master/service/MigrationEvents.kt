package io.craftpanel.master.service

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class MigrationEvent {

    @Serializable
    @SerialName("status")
    data class Status(val status: String) : MigrationEvent()

    @Serializable
    @SerialName("step.started")
    data class StepStarted(val step: Int, val description: String) : MigrationEvent()

    @Serializable
    @SerialName("rsync.progress")
    data class RsyncProgress(val isFinalPass: Boolean, val percent: Int, val bytes: Long, val phase: String) : MigrationEvent()

    @Serializable
    @SerialName("failed")
    data class Failed(val error: String) : MigrationEvent()

    @Serializable
    @SerialName("completed")
    data object Completed : MigrationEvent()
}
