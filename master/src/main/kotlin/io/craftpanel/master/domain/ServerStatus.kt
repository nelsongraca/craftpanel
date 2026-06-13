package io.craftpanel.master.domain

enum class ServerStatus {
    STOPPED, STARTING, HEALTHY, STOPPING, UNHEALTHY;

    val isRunning get() = this in setOf(HEALTHY, STARTING, UNHEALTHY)
    val isStopped get() = this == STOPPED

    fun toDb() = name

    companion object {

        fun fromDb(s: String) = valueOf(s)
    }
}
