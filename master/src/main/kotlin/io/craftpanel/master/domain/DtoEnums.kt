package io.craftpanel.master.domain

import kotlinx.serialization.Serializable

/**
 * Enum types backing DTO string fields that hold a fixed set of values.
 * Constant names match the literal strings persisted in the DB, so
 * [valueOf]/[name] round-trip cleanly. Serialized as their constant name,
 * which is what the OpenAPI spec advertises and the frontend expects.
 */

// NOTE: server_type IS modelled by the domain `ServerType` enum (see ServerType.kt), not a
// plain String. It is a curated set: itzg/minecraft-server accepts a wider, frequently-growing
// set of TYPE values (ARCLIGHT, MAGMA, PUFFERFISH, …), but CraftPanel only provisions types it
// can classify (image, mount path, plugin/mod kind, proxy role). Adding a type is a backend
// change; the enum is exposed on the OpenAPI spec so the frontend derives its type list from it
// instead of duplicating it.

@Serializable
enum class ConfigMode {

    MANAGED,
    MANUAL;

    companion object {

        fun fromDb(s: String) = valueOf(s)
    }
}

@Serializable
enum class BackupTrigger {

    MANUAL,
    SCHEDULED;

    companion object {

        fun fromDb(s: String) = valueOf(s)
    }
}

@Serializable
enum class BackupStatus {

    IN_PROGRESS,
    COMPLETED,
    FAILED;

    companion object {

        fun fromDb(s: String) = valueOf(s)
    }
}

@Serializable
enum class MigrationStatus {

    PENDING,
    SYNCING,
    CUTTING_OVER,
    COMPLETED,
    FAILED,
    CANCELLED,
    RUNNING;

    companion object {

        fun fromDb(s: String) = valueOf(s)
    }
}

@Serializable
enum class MigrationStepStatus {

    PENDING,
    RUNNING,
    SUCCESS,
    FAILED;

    companion object {

        fun fromDb(s: String) = valueOf(s)
    }
}

@Serializable
enum class ModPinStrategy {

    PINNED,
    LATEST,
    BETA,
    ALPHA;

    companion object {

        fun fromDb(s: String) = valueOf(s)
    }
}

/**
 * User-schedulable job types. The constant name is also the value persisted in `server_jobs.type`
 * and the key registered in [io.craftpanel.master.scheduler.ServerScheduler]'s handler map.
 * `BACKUP` is intentionally absent — backup scheduling stays on its dedicated server columns.
 */
@Serializable
enum class ScheduledJobType(val requiresPayload: Boolean) {

    START(requiresPayload = false),
    STOP(requiresPayload = false),
    RESTART(requiresPayload = false),
    RCON_COMMAND(requiresPayload = true);

    companion object {

        fun fromDb(s: String) = valueOf(s)
    }
}
