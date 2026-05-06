# Mods

## Enums

### `pin_strategy`

| Value | Behaviour |
|---|---|
| `PINNED` | A specific Modrinth version ID is locked; itzg downloads exactly that version on restart |
| `LATEST` | itzg downloads the latest compatible version on every restart |

---

## `server_mods`

One row per mod or plugin added to a server. The full list is serialised by master into the `MODRINTH_PROJECTS` environment variable when building the container spec.

| Column | Type | Description |
|---|---|---|
| `id` | UUID | Primary key |
| `server_id` | UUID | FK → `servers`, CASCADE DELETE |
| `modrinth_project_id` | VARCHAR(64) | Modrinth project slug or ID |
| `display_name` | VARCHAR(128) | Cached from Modrinth API; updated on mod list refresh |
| `pin_strategy` | `pin_strategy` ENUM | `PINNED` or `LATEST` |
| `pinned_version_id` | VARCHAR(64) | Modrinth version ID; `NULL` when strategy is `LATEST` |
| `installed_version_id` | VARCHAR(64) | Last known version downloaded by itzg; used for update badge in UI |
| `created_at` | TIMESTAMPTZ | |
| `updated_at` | TIMESTAMPTZ | |
