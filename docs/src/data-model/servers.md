# Servers

## Enums

### `server_type`

Determines the server software and, implicitly, the itzg Docker image used.

| Value | Image | Notes |
|---|---|---|
| `VANILLA` | `itzg/minecraft-server` | |
| `PAPER` | `itzg/minecraft-server` | |
| `FABRIC` | `itzg/minecraft-server` | |
| `FOLIA` | `itzg/minecraft-server` | |
| `FORGE` | `itzg/minecraft-server` | |
| `NEOFORGE` | `itzg/minecraft-server` | |
| `QUILT` | `itzg/minecraft-server` | |
| `SPIGOT` | `itzg/minecraft-server` | |
| `LIMBO` | `itzg/minecraft-server` | |
| `VELOCITY` | `itzg/mc-proxy` | |
| `BUNGEECORD` | `itzg/mc-proxy` | |
| `WATERFALL` | `itzg/mc-proxy` | |

!!! note "Image derivation"
    The Docker image (`itzg/minecraft-server` vs `itzg/mc-proxy`) is **derived from `server_type` in application code** — it is not stored in the database. The mapping is a simple `when` expression in Kotlin. If the itzg project restructures its images, the change requires a code update and redeploy, which is the appropriate forcing function for a deliberate decision.

### `server_status`

| Value | Meaning |
|---|---|
| `STOPPED` | Container is not running |
| `STARTING` | Container is running; itzg is still initialising or downloading |
| `HEALTHY` | Server is accepting connections (itzg health check passing) |
| `UNHEALTHY` | Container is running but health check is failing |

### Migration state

Whether a server is currently being migrated is **not** a separate status value or column on `servers`. Master derives `is_migrating` by checking for an active `migrations` record (status `PENDING`, `SYNCING`, or `CUTTING_OVER`) for the server. This keeps `server_status` clean and avoids a compound state enum.

During migration the server status continues to reflect actual container state:

| Migration phase | `server_status` | Meaning |
|---|---|---|
| Initial rsync (`SYNCING`) | `HEALTHY` | Server still running on source node while data is copied |
| Final delta + cutover (`CUTTING_OVER`) | `STOPPED` | Server stopped on source; new container starting on destination |
| Complete | `HEALTHY` | Server running on destination node; migration record closed |

The UI combines both — showing the server's live status alongside a migration progress indicator driven by the active `migrations` record and its `migration_step_log`.

### `config_mode`

| Value | Meaning |
|---|---|
| `MANAGED` | UI form fields drive itzg env vars; master generates the container spec |
| `MANUAL` | Env var management disabled; user edits config files directly via file explorer |

---

## `servers`

| Column | Type | Description |
|---|---|---|
| `id` | UUID | Primary key |
| `display_name` | VARCHAR(64) | |
| `description` | TEXT | Optional |
| `server_type` | `server_type` ENUM | Determines software and Docker image |
| `mc_version` | VARCHAR(16) | Minecraft version string, e.g. `1.21.4`; maps to itzg `VERSION` env var |
| `itzg_image_tag` | VARCHAR(64) | itzg image tag, e.g. `latest` or a pinned digest |
| `node_id` | UUID | FK → `nodes`, RESTRICT — server must be migrated before node decommission |
| `network_id` | UUID | FK → `server_networks`, SET NULL — nullable |
| `status` | `server_status` ENUM | Current runtime state |
| `config_mode` | `config_mode` ENUM | `MANAGED` or `MANUAL` |
| `ram_mb` | INT | RAM allocated to this container |
| `cpu_shares` | INT | Docker CPU share value |
| `host_port` | INT | Port assigned from the node port registry; `NULL` for containers only reachable within a Docker bridge network |
| `exposed_externally` | BOOLEAN | Whether a public DNS A record exists for this server |
| `public_subdomain` | VARCHAR(64) | Chosen subdomain, e.g. `survival`; `NULL` if not exposed. Unique across all servers |
| `public_hostname` | VARCHAR(255) | Fully-qualified public hostname, e.g. `survival.mc.domain.tld` |
| `dns_record_id` | TEXT | DNS provider record ID; used for updates and deletion on migration or exposure toggle |
| `container_id` | TEXT | Docker container ID as last reported by agent; `NULL` when stopped |
| `container_name` | TEXT | Stable container name; also used as the Docker bridge hostname for same-node routing |
| `player_count` | INT | Last observed player count from Minecraft query protocol; refreshed every 30 s |
| `player_list` | JSONB | Array of online player name strings |
| `last_seen_at` | TIMESTAMPTZ | Timestamp of last successful health check from agent |
| `stop_command` | VARCHAR(64) | Command written to container stdin on graceful stop or restart. Defaults to `stop` for game servers and `end` for proxy types. Configurable per server in the UI |
| `backup_schedule` | VARCHAR(64) | Cron expression for automated backups; `NULL` = no scheduled backups |
| `backup_max_count` | INT | Maximum number of backups to retain; default `10` |
| `created_at` | TIMESTAMPTZ | |
| `updated_at` | TIMESTAMPTZ | |

---

## `server_env_vars`

Stores the managed-mode configuration for a server. Each row represents one itzg environment variable. Master reads this table when building the container spec on create or restart.

| Column | Type | Description |
|---|---|---|
| `server_id` | UUID | FK → `servers`, CASCADE DELETE |
| `key` | VARCHAR(128) | itzg env var name, e.g. `DIFFICULTY`, `MAX_PLAYERS`, `MOTD` |
| `value` | TEXT | String value; numeric and boolean values stored as strings per Docker convention |

**Primary key:** `(server_id, key)`

!!! note
    When `config_mode` is `MANUAL`, this table is not applied to the container spec. Values are preserved so they can be restored if the server is switched back to managed mode.

---

## `server_container_metrics`

Per-container resource snapshots sourced from the Docker Stats API, collected by the agent and forwarded to master.

| Column | Type | Description |
|---|---|---|
| `id` | UUID | Primary key |
| `server_id` | UUID | FK → `servers`, CASCADE DELETE |
| `recorded_at` | TIMESTAMPTZ | Snapshot timestamp |
| `cpu_percent` | NUMERIC(5,2) | Container CPU utilisation |
| `ram_used_mb` | INT | |
| `net_in_bytes` | BIGINT | Bytes received since last snapshot |
| `net_out_bytes` | BIGINT | Bytes sent since last snapshot |

**Index:** `(server_id, recorded_at DESC)`

Subject to the same `metric_retention_days` retention policy as `node_metrics`.

!!! warning "Future consideration"
    See the note in [Nodes](nodes.md#node_metrics) — this table shares the same time-series extraction considerations as `node_metrics`.

---

## `port_registry`

Tracks port assignments per node. Each server that needs a host-mapped port is assigned one from the node's configured range at creation time. Ports are reclaimed when the server is deleted.

| Column | Type | Description |
|---|---|---|
| `node_id` | UUID | FK → `nodes`, CASCADE DELETE |
| `port` | INT | Port number |
| `server_id` | UUID | FK → `servers`, SET NULL on server delete — `NULL` indicates the port is free |

**Primary key:** `(node_id, port)`

---

## `proxy_backends`

Records which backend game servers a proxy server connects to, used in managed (easy) mode to generate `velocity.toml` or `config.yml`. Ignored when the proxy's `config_mode` is `MANUAL`.

| Column | Type | Description |
|---|---|---|
| `id` | UUID | Primary key |
| `proxy_server_id` | UUID | FK → `servers` (must be a proxy type), CASCADE DELETE |
| `backend_server_id` | UUID | FK → `servers` (must be a game server type), CASCADE DELETE |
| `backend_name` | VARCHAR(64) | Internal name used in the generated proxy config, e.g. `survival`, `creative` |
| `order` | INT | Ordering for display and config generation |

**Unique constraint:** `(proxy_server_id, backend_name)`
