package io.craftpanel.master.service

import io.craftpanel.master.domain.ServerType

sealed interface Classification {
    data class Eligible(val file: String) : Classification
    data class WarnSkip(val reason: String) : Classification
}

object BackendForwarding {

    private val PAPER_LINEAGE = setOf(ServerType.PAPER, ServerType.PURPUR)
    private val SPIGOT_LINEAGE = setOf(ServerType.SPIGOT, ServerType.BUKKIT)
    private val ELIGIBLE = PAPER_LINEAGE + SPIGOT_LINEAGE

    fun classify(serverType: ServerType, mode: String): Classification {
        if (serverType !in ELIGIBLE) {
            return Classification.WarnSkip("$serverType does not support forwarding")
        }

        return when (mode) {
            "MODERN" ->
                if (serverType in PAPER_LINEAGE) {
                    Classification.Eligible("/data/config/paper-global.yml")
                } else {
                    Classification.WarnSkip("$serverType does not support modern (Velocity) forwarding — only Paper lineage does")
                }

            "LEGACY" -> Classification.Eligible("/data/spigot.yml")

            else -> Classification.WarnSkip("Unsupported forwarding mode '$mode' for $serverType")
        }
    }
}
