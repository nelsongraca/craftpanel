package io.craftpanel.master.domain

import kotlinx.serialization.Serializable

@Serializable
enum class ServerType {

    VANILLA,
    PAPER,
    PURPUR,
    SPIGOT,
    BUKKIT,
    FABRIC,
    FORGE,
    NEOFORGE,
    QUILT,
    VELOCITY,
    BUNGEECORD,
    WATERFALL;

    val isProxy get() = this in PROXY_TYPES
    val supportsPlugins get() = this in PLUGIN_TYPES

    fun toDb() = name

    companion object {

        fun fromDb(s: String) = valueOf(s)

        private val PROXY_TYPES = setOf(VELOCITY, BUNGEECORD, WATERFALL)
        private val PLUGIN_TYPES = setOf(PAPER, PURPUR, SPIGOT, BUKKIT, VELOCITY, BUNGEECORD, WATERFALL)

        val LOADER_BY_TYPE = mapOf(
            FABRIC to "fabric", FORGE to "forge", NEOFORGE to "neoforge", QUILT to "quilt",
            PAPER to "paper", PURPUR to "purpur", SPIGOT to "spigot", BUKKIT to "bukkit",
            VELOCITY to "velocity", BUNGEECORD to "bungeecord", WATERFALL to "waterfall"
        )
    }
}
