# Migrations

## Enums

### `migration_status`

| Value          | Meaning                                                 |
|----------------|---------------------------------------------------------|
| `PENDING`      | Migration created; initial rsync not yet started        |
| `SYNCING`      | Initial rsync pass in progress (server still running)   |
| `CUTTING_OVER` | Final rsync, container swap, and DNS update in progress |
| `COMPLETED`    | Server is fully running on destination node             |
| `FAILED`       | Migration failed at some step; see `error_message`      |

---

## `migrations`

Tracks a server migration operation from initiation to completion. One record per migration attempt. This table is also the **source of truth for whether a server is currently migrating** — master
derives `is_migrating` for any server by checking for an active record with status `PENDING`, `SYNCING`, or `CUTTING_OVER`. No separate flag is stored on the `servers` table.

| Column                | Type                    | Description                                                                              |
|-----------------------|-------------------------|------------------------------------------------------------------------------------------|
| `id`                  | UUID                    | Primary key                                                                              |
| `server_id`           | UUID                    | FK → `servers`, CASCADE DELETE                                                           |
| `source_node_id`      | UUID                    | FK → `nodes` — retained for audit even after completion                                  |
| `destination_node_id` | UUID                    | FK → `nodes`                                                                             |
| `initiated_by`        | UUID                    | FK → `users`                                                                             |
| `status`              | `migration_status` ENUM | Overall migration state                                                                  |
| `current_step`        | INT                     | Last completed step (1–11); maps to steps defined in the migration spec                  |
| `warning_message`     | TEXT                    | In-game RCON broadcast sent to players at step 4, e.g. `Server restarting in 60 seconds` |
| `error_message`       | TEXT                    | `NULL` unless status is `FAILED`                                                         |
| `created_at`          | TIMESTAMPTZ             |                                                                                          |
| `updated_at`          | TIMESTAMPTZ             |                                                                                          |

---

## `migration_step_log`

Records a timestamped entry for each step as it completes (or fails). Useful for diagnosing slow or failed migrations — e.g. identifying that a large initial rsync took unexpectedly long.

| Column         | Type        | Description                                                |
|----------------|-------------|------------------------------------------------------------|
| `id`           | UUID        | Primary key                                                |
| `migration_id` | UUID        | FK → `migrations`, CASCADE DELETE                          |
| `step`         | INT         | Step number (1–11)                                         |
| `status`       | VARCHAR(16) | `STARTED`, `COMPLETED`, or `FAILED`                        |
| `message`      | TEXT        | Optional detail, e.g. bytes transferred, error description |
| `recorded_at`  | TIMESTAMPTZ | When this log entry was written                            |

**Index:** `(migration_id, step, recorded_at)`

### Migration steps reference

| Step | Action                                                                   |
|------|--------------------------------------------------------------------------|
| 1    | Allocate rsync port on destination node                                  |
| 2    | Prepare rsync receiver on destination node                               |
| 3    | Initial rsync pass (server remains running)                              |
| 4    | In-game warning broadcast via RCON                                       |
| 5    | Source container stopped (awaits `STOPPED`); container retained for sync |
| 6    | Final incremental rsync (source stopped → consistent snapshot)           |
| 7    | Source container removed (awaits confirmation; frees container name)     |
| 8    | New container created and started on destination node (awaits `HEALTHY`) |
| 9    | DNS A record updated to destination node IP                              |
| 10   | mc-router label set on new container (at creation); ingress live         |
| 11   | Server node assignment and port registry updated in database             |
