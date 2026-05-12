# Server Networks

## `server_networks`

A Server Network is a logical grouping of servers — typically one proxy and one or more backend game servers — that form a single player-facing Minecraft network. The network record itself is minimal; membership is expressed via the `network_id` foreign key on each `servers` row.

| Column | Type | Description |
|---|---|---|
| `id` | UUID | Primary key |
| `display_name` | VARCHAR(64) | Human-readable network name |
| `description` | TEXT | Optional |
| `created_at` | TIMESTAMPTZ | |
| `updated_at` | TIMESTAMPTZ | |

!!! note
    Networks are administrator-managed. Deleting a network does not delete its member servers — `network_id` on affected servers is set to `NULL`.
