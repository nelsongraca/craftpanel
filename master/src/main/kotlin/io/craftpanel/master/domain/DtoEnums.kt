package io.craftpanel.master.domain

import kotlinx.serialization.Serializable

/**
 * Enum types backing DTO string fields that hold a fixed set of values.
 * Constant names match the literal strings persisted in the DB, so
 * [valueOf]/[name] round-trip cleanly. Serialized as their constant name,
 * which is what the OpenAPI spec advertises and the frontend expects.
 */

// NOTE: server_type is intentionally NOT an enum. itzg/minecraft-server accepts an
// open-ended, frequently-growing set of TYPE values (PAPER, FABRIC, FORGE, NEOFORGE,
// QUILT, SPIGOT, FOLIA, LIMBO, PURPUR, …). The backend only special-cases the three
// proxy types; everything else falls through to the minecraft image. Modelling it as
// an enum would reject valid server types on deserialization. Kept as String.

@Serializable
enum class ConfigMode {

    MANAGED, MANUAL;

    companion object {

        fun fromDb(s: String) = valueOf(s)
    }
}

@Serializable
enum class BackupTrigger {

    MANUAL, SCHEDULED;

    companion object {

        fun fromDb(s: String) = valueOf(s)
    }
}

@Serializable
enum class BackupStatus {

    IN_PROGRESS, COMPLETED, FAILED;

    companion object {

        fun fromDb(s: String) = valueOf(s)
    }
}

@Serializable
enum class MigrationStatus {

    PENDING, SYNCING, CUTTING_OVER, COMPLETED, FAILED, CANCELLED, RUNNING;

    companion object {

        fun fromDb(s: String) = valueOf(s)
    }
}

@Serializable
enum class MigrationStepStatus {

    PENDING, RUNNING, SUCCESS, FAILED;

    companion object {

        fun fromDb(s: String) = valueOf(s)
    }
}

@Serializable
enum class ModPinStrategy {

    PINNED, LATEST, BETA, ALPHA;

    companion object {

        fun fromDb(s: String) = valueOf(s)
    }
}
