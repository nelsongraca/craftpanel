package io.craftpanel.master.domain

import kotlinx.serialization.Serializable

@Serializable
enum class NodeStatus {

    PENDING, ACTIVE, REJECTED, DECOMMISSIONED;

    fun toDb() = name

    companion object {

        fun fromDb(s: String) = valueOf(s)
    }
}
