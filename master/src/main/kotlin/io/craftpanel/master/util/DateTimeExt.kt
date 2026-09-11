package io.craftpanel.master.util

import kotlinx.datetime.LocalDateTime

/**
 * Renders a `LocalDateTime` (UTC) as `<ISO8601>Z`. `LocalDateTime.toString()`
 * drops seconds (and trailing nanos) when they are zero (e.g. `2027-03-15T09:30`),
 * which `kotlin.time.Instant.parse` rejects — so seconds are always padded in.
 * Keep this parseable by both [parseUtcInstant] and kotlin.time.Instant.parse.
 */
fun LocalDateTime.toUtcString(): String {
    val base = "${this}"
    val withSeconds = if (base.length <= 16) "$base:00" else base
    return "${withSeconds}Z"
}

/**
 * Parses a UTC timestamp string produced by [toUtcString] (or a legacy un-padded
 * variant such as `2027-03-15T09:30Z`) into a [kotlin.time.Instant]. Returns null
 * for anything unparseable. Tolerates the short `HH:mm` form by padding to seconds.
 */
fun parseUtcInstant(raw: String): kotlin.time.Instant? {
    val zIdx = raw.indexOf('Z')
    val padded = if (zIdx > 0 && raw.substring(0, zIdx).length == 16) {
        raw.replaceRange(zIdx, zIdx, ":00")
    } else {
        raw
    }
    return runCatching { kotlin.time.Instant.parse(padded) }.getOrNull()
}

/**
 * Formats a `Backups.created_at` value (a `LocalDateTime` rendered as a string
 * by Exposed, e.g. `2026-07-18T14:30:00` or `2026-07-18 14:30:00`) into the
 * `yyyy-MM-dd_HH-mm-ss` form used for backups-by-server symlink file names.
 * `LocalDateTime.toString()` is already zero-padded, so this just swaps the `T`/space
 * separator for `_` and the `:` time separators for `-`. Single source of truth shared
 * by backup trigger, backup delete, and reconnect rebuild so create/delete agree.
 */
fun formatSymlinkTimestamp(rawCreatedAt: String): String = rawCreatedAt.replace('T', ' ').replace(':', '-').replace(' ', '_')
