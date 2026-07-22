package io.craftpanel.master.service

import io.craftpanel.master.domain.ServerType

sealed interface Classification {
    data class Eligible(val file: String) : Classification
    data class WarnSkip(val reason: String) : Classification
}

object BackendForwarding {

    fun classify(serverType: ServerType, mode: String): Classification {
        val knownPaper = setOf(ServerType.PAPER, ServerType.PURPUR)
        val knownSpigot = setOf(ServerType.SPIGOT, ServerType.BUKKIT)
        val eligible = knownPaper + knownSpigot

        if (serverType !in eligible) {
            return Classification.WarnSkip("$serverType does not support forwarding")
        }

        return when (mode) {
            "MODERN" -> classifyModern(serverType, knownPaper)
            "LEGACY" -> classifyLegacy(serverType, knownSpigot)
            else -> Classification.WarnSkip("Unsupported forwarding mode '$mode' for $serverType")
        }
    }

    private fun classifyModern(serverType: ServerType, knownPaper: Set<ServerType>): Classification {
        if (serverType in knownPaper) {
            return Classification.Eligible("/data/config/paper-global.yml")
        }
        return Classification.WarnSkip("$serverType does not support modern (Velocity) forwarding — only Paper lineage does")
    }

    private fun classifyLegacy(serverType: ServerType, knownSpigot: Set<ServerType>): Classification = Classification.Eligible("/data/spigot.yml")
}
