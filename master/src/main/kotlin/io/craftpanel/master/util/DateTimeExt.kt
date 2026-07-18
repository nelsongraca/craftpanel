package io.craftpanel.master.util

import kotlinx.datetime.LocalDateTime

fun LocalDateTime.toUtcString(): String = "${this}Z"

/**
 * Formats a `Backups.created_at` value (a `LocalDateTime` rendered as a string
 * by Exposed, e.g. `2026-07-18T14:30:00` or `2026-07-18 14:30:00`) into the
 * `yyyy-MM-dd_HH-mm-ss` form used for backups-by-server symlink file names.
 * `LocalDateTime.toString()` is already zero-padded, so this just swaps the `T`/space
 * separator for `_` and the `:` time separators for `-`. Single source of truth shared
 * by backup trigger, backup delete, and reconnect rebuild so create/delete agree.
 */
fun formatSymlinkTimestamp(rawCreatedAt: String): String = rawCreatedAt.replace('T', ' ').replace(':', '-').replace(' ', '_')
