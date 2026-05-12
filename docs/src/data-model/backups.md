# Backups

## Enums

### `backup_trigger`

| Value | Meaning |
|---|---|
| `MANUAL` | Triggered by a user action |
| `SCHEDULED` | Triggered by master's cron scheduler |

### `backup_status`

| Value | Meaning |
|---|---|
| `IN_PROGRESS` | Agent is currently creating the archive |
| `COMPLETED` | Archive created successfully; metadata recorded |
| `FAILED` | Archive creation failed; see `error_message` |

---

## `backups`

| Column | Type | Description |
|---|---|---|
| `id` | UUID | Primary key |
| `server_id` | UUID | FK → `servers`, CASCADE DELETE |
| `node_id` | UUID | FK → `nodes` — denormalised; the node that holds the backup file. Retained even if the server later migrates to a different node |
| `trigger` | `backup_trigger` ENUM | How the backup was initiated |
| `status` | `backup_status` ENUM | Current or final state |
| `file_path` | TEXT | Absolute path on the node, e.g. `/data/craftpanel/backups/<server-id>/<backup-id>.tar.gz` |
| `size_bytes` | BIGINT | Archive size; `NULL` until status is `COMPLETED` |
| `error_message` | TEXT | `NULL` unless status is `FAILED` |
| `created_at` | TIMESTAMPTZ | When the backup was triggered |
| `completed_at` | TIMESTAMPTZ | `NULL` until finished |

!!! note
    Retention is enforced by master before each new backup: if the server's `backup_max_count` limit is reached, the oldest `COMPLETED` backup is deleted first (agent removes the file, master removes the row). `IN_PROGRESS` and `FAILED` records do not count toward the limit.
