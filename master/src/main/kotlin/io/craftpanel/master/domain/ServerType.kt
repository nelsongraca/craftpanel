package io.craftpanel.master.domain

import kotlinx.serialization.Serializable

/** Which kind of downloadable artifact a [ServerType] loads. */
enum class ArtifactKind { MOD, PLUGIN, NONE }

@Serializable
enum class ServerType {

    VANILLA,
    PAPER,
    PURPUR,

    // Paper fork with regionised multithreading; plugins in plugins/, itzg TYPE=FOLIA.
    FOLIA,
    SPIGOT,
    BUKKIT,
    FABRIC,
    FORGE,
    NEOFORGE,
    QUILT,

    // LOOHP Limbo — lightweight lobby server, itzg TYPE=LIMBO. No world/plugin API.
    LIMBO,
    VELOCITY,
    BUNGEECORD,
    WATERFALL,

    // Arbitrary server jar (itzg TYPE=CUSTOM + CUSTOM_SERVER). Not managed by
    // CraftPanel — itzg runs the jar as-is, no server.properties auto-config.
    CUSTOM,

    // Native Rust binary — PicoLimbo runs as a standalone container (ghcr.io/quozul/picolimbo).
    // No itzg wrapper, no JVM, no server.properties auto-config. Config lives in server.toml
    // mounted at the container working directory (/usr/src/app).
    PICOLIMBO;

    val isProxy get() = this in PROXY_TYPES
    val supportsPlugins get() = this in PLUGIN_TYPES
    val isModLoader get() = this in MOD_LOADER_TYPES
    val isCustom get() = this == CUSTOM
    val isPicolimbo get() = this == PICOLIMBO

    /** The kind of downloadable artifact this type loads — guards a type change against installed mods. */
    val artifactKind: ArtifactKind
        get() = when {
            isModLoader -> ArtifactKind.MOD
            supportsPlugins -> ArtifactKind.PLUGIN
            else -> ArtifactKind.NONE
        }

    fun toDb() = name

    companion object {

        fun fromDb(s: String) = valueOf(s)

        private val PROXY_TYPES = setOf(VELOCITY, BUNGEECORD, WATERFALL)
        private val PLUGIN_TYPES = setOf(PAPER, PURPUR, FOLIA, SPIGOT, BUKKIT, VELOCITY, BUNGEECORD, WATERFALL)
        private val MOD_LOADER_TYPES = setOf(FABRIC, FORGE, NEOFORGE, QUILT)

        val LOADER_BY_TYPE = mapOf(
            FABRIC to "fabric", FORGE to "forge", NEOFORGE to "neoforge", QUILT to "quilt",
            PAPER to "paper", PURPUR to "purpur", FOLIA to "folia", SPIGOT to "spigot", BUKKIT to "bukkit",
            VELOCITY to "velocity", BUNGEECORD to "bungeecord", WATERFALL to "waterfall"
        )
    }
}
