# Migrations

## Enums

### `migration_status`

| Value | Meaning |
|---|---|
| `PENDING` | Migration created; initial rsync not yet started |
| `SYNCING` | Initial rsync pass in progress (server still running) |
| `CUTTING_OVER` | Final rsync, container swap, and DNS update in progress |
| `COMPLETED` | Server is fully running on destination node |
| `FAILED` | Migration failed at some step; see `error_message` |

---

## `migrations`

Tracks a server migration operation from initiation to completion. One record per migration attempt. This table is also the **source of truth for whether a server is currently migrating** — master derives `is_migrating` for any server by checking for an active record with status `PENDING`, `SYNCING`, or `CUTTING_OVER`. No separate flag is stored on the `servers` table.

| Column | Type | Description |
|---|---|---|
| `id` | UUID | Primary key |
| `server_id` | UUID | FK → `servers`, CASCADE DELETE |
| `source_node_id` | UUID | FK → `nodes` — retained for audit even after completion |
| `destination_node_id` | UUID | FK → `nodes` |
| `initiated_by` | UUID | FK → `users` |
| `status` | `migration_status` ENUM | Overall migration state |
| `current_step` | INT | Last completed step (1–11); maps to steps defined in the migration spec |
| `warning_message` | TEXT | In-game RCON broadcast sent to players at step 4, e.g. `Server restarting in 60 seconds` |
| `error_message` | TEXT | `NULL` unless status is `FAILED` |
| `created_at` | TIMESTAMPTZ | |
| `updated_at` | TIMESTAMPTZ | |

---

## `migration_step_log`

Records a timestamped entry for each step as it completes (or fails). Useful for diagnosing slow or failed migrations — e.g. identifying that a large initial rsync took unexpectedly long.

| Column | Type | Description |
|---|---|---|
| `id` | UUID | Primary key |
| `migration_id` | UUID | FK → `migrations`, CASCADE DELETE |
| `step` | INT | Step number (1–11) |
| `status` | VARCHAR(16) | `STARTED`, `COMPLETED`, or `FAILED` |
| `message` | TEXT | Optional detail, e.g. bytes transferred, error description |
| `recorded_at` | TIMESTAMPTZ | When this log entry was written |

**Index:** `(migration_id, step, recorded_at)`

### Migration steps reference

| Step | Action |
|---|---|
| 1 | Migration initiated; source and destination nodes selected |
| 2 | Initial rsync started (server remains running) |
| 3 | Initial rsync complete; destination agent confirmed receipt |
| 4 | In-game warning broadcast via RCON |
| 5 | RCON `save-all` + `save-off` sent to source server |
| 6 | Final incremental rsync (delta only) |
| 7 | New container created and started on destination node |
| 8 | DNS A record updated to destination node IP |
| 9 | mc-router label set on new container; ingress live on destination |
| 10 | Old container stopped and removed on source node |
| 11 | Server node assignment updated in database |
