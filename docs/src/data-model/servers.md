# Servers

## Enums

### `server_type`

Determines the server software and, implicitly, the itzg Docker image used.

| Value        | Image                   | Notes |
|--------------|-------------------------|-------|
| `VANILLA`    | `itzg/minecraft-server` |       |
| `PAPER`      | `itzg/minecraft-server` |       |
| `FABRIC`     | `itzg/minecraft-server` |       |
| `FOLIA`      | `itzg/minecraft-server` |       |
| `FORGE`      | `itzg/minecraft-server` |       |
| `NEOFORGE`   | `itzg/minecraft-server` |       |
| `QUILT`      | `itzg/minecraft-server` |       |
| `SPIGOT`     | `itzg/minecraft-server` |       |
| `LIMBO`      | `itzg/minecraft-server` |       |
| `CUSTOM`     | `itzg/minecraft-server` | Runs an arbitrary server jar (`TYPE=CUSTOM` + `CUSTOM_SERVER`) |
| `VELOCITY`   | `itzg/mc-proxy`         |       |
| `BUNGEECORD` | `itzg/mc-proxy`         |       |
| `WATERFALL`  | `itzg/mc-proxy`         |       |

!!! note "Image derivation"
The Docker image (`itzg/minecraft-server` vs `itzg/mc-proxy`) is **derived from `server_type` in application code** — it is not stored in the database. The mapping is a simple `when` expression in
Kotlin. If the itzg project restructures its images, the change requires a code update and redeploy, which is the appropriate forcing function for a deliberate decision.

### `server_status`

`servers.status` stores only what the **agent reports**. `STARTING` and `STOPPING` are
**synthesized at read time** (see `synthesizeStatus`) from the stored intent
(`servers.desired_status`) plus the reported status — they are never persisted.

| Value          | Stored? | Meaning                                                                            |
|----------------|---------|------------------------------------------------------------------------------------|
| `STOPPED`      | yes     | Container is not running                                                           |
| `HEALTHY`      | yes     | Server is accepting connections (itzg health check passing)                        |
| `UNHEALTHY`    | yes     | Container is running but health check is failing                                   |
| `CRASH_LOOPED` | yes     | Crash-restart budget exhausted; agent left the container stopped for manual action |
| `STARTING`     | no      | Synthesized: desired `RUNNING` but not yet reported `HEALTHY`                      |
| `STOPPING`     | no      | Synthesized: desired `STOPPED` but the container has not stopped yet               |

### Container restart ownership

Managed containers run with Docker restart policy **`no`** — Docker never auto-restarts them. Restart-on-crash is **owned by the agent**, not master and not Docker. Master states
intent (`desired_status`) + the full runtime spec + a restart budget; the agent converges and restarts crashed servers within that budget. Whether to restart is decided **only by
desired state** — the Docker exit code is informational (a self-exit is restarted while desired stays `RUNNING`; an authored stop sets desired `STOPPED` first, so it is not).

When a managed container dies, the agent's Docker `die` watcher fires. Authored deaths (stop/remove/recreate) are suppressed by the watcher gate; a genuine unexpected death
triggers a converge. The gate is seeded from every desired-state envelope, so ownership survives an agent process restart even for a container the agent did not itself start this
run. If desired state is `RUNNING` and the crash count is within budget (`restart_max_attempts`, default 5, within `restart_window_seconds`, default 600, both from system settings
and shipped in every `ServerDesiredState` envelope), the agent restarts the container. Exhausting the budget leaves the container stopped and the agent reports `CRASH_LOOPED`; a
manual start re-converges. A successful start resets the counter. A `no_restart` flag (used during live migration) suppresses autonomous restart while desired stays `RUNNING`.

Two backstops cover a death whose `die` event is lost (agent/Docker daemon restart, dropped event stream): the watcher re-subscribes with exponential backoff, and the agent
periodically re-converges any server with intent that is not running (`AGENT_RECONCILE_INTERVAL_SECONDS`, default 30, `0` disables).

### Desired state vs reported state

`servers.desired_status` (`RUNNING`/`STOPPED`, nullable) is master's intent. `servers.status` is the agent's report. The status surfaced by the API is
`synthesizeStatus(desired, reported)`:

| desired | reported                | shown        |
|---------|-------------------------|--------------|
| RUNNING | HEALTHY                 | HEALTHY      |
| RUNNING | STARTING                | STARTING     |
| RUNNING | STOPPED                 | STARTING     |
| RUNNING | UNHEALTHY               | UNHEALTHY    |
| RUNNING | CRASH_LOOPED            | CRASH_LOOPED |
| STOPPED | STOPPED                 | STOPPED      |
| STOPPED | STARTING / HEALTHY / UNHEALTHY | STOPPING |
| STOPPED | CRASH_LOOPED            | STOPPED      |
| unset   | (any)                   | reported     |

### Migration state

Whether a server is currently being migrated is **not** a separate status value or column on `servers`. Master derives `is_migrating` by checking for an active `migrations` record (status `PENDING`,
`SYNCING`, or `CUTTING_OVER`) for the server. This keeps `server_status` clean and avoids a compound state enum.

During migration the server status continues to reflect actual container state:

| Migration phase                        | `server_status` | Meaning                                                         |
|----------------------------------------|-----------------|-----------------------------------------------------------------|
| Initial rsync (`SYNCING`)              | `HEALTHY`       | Server still running on source node while data is copied        |
| Final delta + cutover (`CUTTING_OVER`) | `STOPPED`       | Server stopped on source; new container starting on destination |
| Complete                               | `HEALTHY`       | Server running on destination node; migration record closed     |

The UI combines both — showing the server's live status alongside a migration progress indicator driven by the active `migrations` record and its `migration_step_log`.

### `config_mode`

| Value     | Meaning                                                                         |
|-----------|---------------------------------------------------------------------------------|
| `MANAGED` | UI form fields drive itzg env vars; master generates the container spec         |
| `MANUAL`  | Env var management disabled; user edits config files directly via file explorer |

!!! note "CUSTOM servers"
    `CUSTOM` servers are always `MANUAL` — the config mode toggle is rejected server-side. The managed-mode defaults are never generated for them; only extra env vars and the stop command are editable.

---

## `servers`

| Column               | Type                 | Description                                                                                                                                                      |
|----------------------|----------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `id`                 | UUID                 | Primary key                                                                                                                                                      |
| `name`               | VARCHAR(100)         | Unique machine-readable slug, e.g. `survival-1`; used for container naming and internal references                                                               |
| `display_name`       | VARCHAR(100)         | Human-readable label shown in the UI                                                                                                                             |
| `description`        | VARCHAR(500)        | Optional                                                                                                                                                        |
| `server_type`        | VARCHAR(20)          | Determines software and Docker image; validated at application layer                                                                                             |
| `mc_version`         | VARCHAR(16)          | Minecraft version string, e.g. `1.21.4`; maps to itzg `VERSION` env var                                                                                          |
| `itzg_image_tag`     | VARCHAR(100)         | itzg image tag, e.g. `latest` or a pinned digest                                                                                                                 |
| `custom_server_jar`  | VARCHAR(512)         | Required for `CUSTOM` servers. URL of the server jar to download and run; maps to itzg `CUSTOM_SERVER`. `NULL` for all other types                                 |
| `container_listen_port` | INT               | Port the server listens on **inside** the container; maps to itzg `SERVER_PORT`. `NULL` = derived from type (`25565` game / `25577` proxy)                        |
| `container_protocol` | VARCHAR(4)           | `TCP` or `UDP`. `UDP` produces UDP host-port bindings only and skips mc-router labels (mc-router is TCP-only)                                                    |
| `disable_healthcheck` | BOOLEAN             | When `true`, itzg runs with `DISABLE_HEALTHCHECK=true`; default `false`                                                                                           |
| `force_redownload`   | BOOLEAN              | When `true`, itzg re-downloads the server jar on each start (`FORCE_REDOWNLOAD=true`); default `false`                                                           |
| `node_id`            | UUID                 | FK → `nodes`, RESTRICT — server must be migrated before node decommission                                                                                        |
| `network_id`         | UUID                 | FK → `server_networks`, SET NULL — nullable                                                                                                                      |
| `desired_status`     | VARCHAR(10)          | Master's intent: `RUNNING` or `STOPPED`; `NULL` = unset. Drives the read-time status synthesis                                                                   |
| `status`             | VARCHAR(10)          | Agent-reported runtime state: `STOPPED`, `HEALTHY`, `UNHEALTHY`, `CRASH_LOOPED` (`STARTING`/`STOPPING` are synthesized, never stored)                             |
| `config_mode`        | VARCHAR(10)          | `MANAGED` or `MANUAL`                                                                                                                                            |
| `memory_mb`          | INT                  | RAM allocated to this container                                                                                                                                  |
| `cpu_shares`         | INT                  | Docker CPU share value; `0` = unlimited                                                                                                                          |
| `host_port`          | INT                  | Port assigned from the node port registry. Always set — every server currently allocates a host port at creation                                                |
| `exposed_externally` | BOOLEAN              | Whether a public DNS A record exists for this server                                                                                                             |
| `public_subdomain`   | VARCHAR(253)         | Chosen subdomain, e.g. `survival`; `NULL` if not exposed. Unique across all servers. The fully-qualified public hostname is derived at runtime from this + the base domain in system settings — not stored |
| `dns_record_id`      | VARCHAR(100)         | DNS provider record ID; used for updates and deletion on migration or exposure toggle                                                                            |
| `dns_record_name`    | VARCHAR(255)         | DNS provider record name as returned by the provider                                                                                                             |
| `custom_hostname`    | VARCHAR(1000)        | Comma-separated list of user-supplied fully-qualified hostnames (e.g. `play.their-domain.com,creative.their-domain.com`); `NULL` if none set. Each hostname must be unique across all servers and is validated individually. The panel configures mc-router routing for all of them but never manages DNS — the user must point their own A/CNAME records at the node. Set/change/clear does not restart the server: it flags `restart_pending` and the mc-router label updates on the next start/restart. Additive with the managed subdomain: the managed hostname and every custom hostname can route simultaneously. |
| `restart_pending`    | BOOLEAN              | UI-only marker: a spec-affecting change was saved that the running container has not applied yet. Set by config writers (env vars, resources, exposure, mods, extra ports, proxy settings, data-dir override); cleared when the agent reports a `STARTING` transition. Master never uses it to drive convergence — the agent derives recreate from `spec != applied` |

!!! note "Canonical public hostname"
    The **canonical hostname** shown on the server detail page (API field `canonical_hostname`) is derived as: the first entry of `custom_hostname` if set, otherwise the fully-qualified managed hostname (`{public_subdomain}.{domain_suffix}`). When neither is available the field is `null`.

    The mc-router label `mc-router.host` is always the **comma-joined** list of all available hostnames (`[managedHostname, customHostname]`, nulls filtered). Both hostnames can route simultaneously.

| `last_player_count`       | INT                  | Last observed player count from Minecraft query protocol; refreshed every 60 s; `NULL` when unknown                                                   |
| `last_player_names`       | VARCHAR(1000)        | Comma-separated string of online player names; `NULL` when unknown                                                                                    |
| `last_player_update`      | TIMESTAMPTZ          | Timestamp of the most recent player data refresh; `NULL` if never polled                                                                              |
| `last_seen_at`       | TIMESTAMPTZ          | Timestamp of last successful health check from agent                                                                                                             |
| `stop_command`       | VARCHAR(64)          | Graceful stop/restart action. Text = command written to container stdin; `^C` = SIGINT, `^\` = SIGQUIT, any `SIG*` name = that signal, delivered to container PID 1 via `docker kill --signal`. Empty = skip to Docker stop. Defaults to `stop` for game servers and `end` for proxy types. Configurable per server in the UI |
| `backup_schedule`    | VARCHAR(64)          | Cron expression for automated backups; `NULL` = no scheduled backups                                                                                             |
| `backup_schedule_last_fired` | TIMESTAMPTZ | When the cron-based backup last triggered; `NULL` if never fired. Read from `ServerJobs.last_fired_at` and cached here for quick lookup                         |
| `backup_max_count`   | INT                  | Maximum number of backups to retain; default `10`                                                                                                                |
| `created_at`         | TIMESTAMPTZ          |                                                                                                                                                                  |
| `updated_at`         | TIMESTAMPTZ          |                                                                                                                                                                  |

---

## `server_env_vars`

Stores the managed-mode configuration for a server. Each row represents one itzg environment variable. Master reads this table when building the container spec on create or restart.

| Column      | Type         | Description                                                                      |
|-------------|--------------|----------------------------------------------------------------------------------|
| `id`        | UUID         | Primary key                                                                      |
| `server_id` | UUID         | FK → `servers`, CASCADE DELETE                                                   |
| `key`       | VARCHAR(128) | itzg env var name, e.g. `DIFFICULTY`, `MAX_PLAYERS`, `MOTD`                      |
| `value`     | TEXT         | String value; numeric and boolean values stored as strings per Docker convention |

**Primary key:** `id` &nbsp;&nbsp; **Unique constraint:** `(server_id, key)`

!!! note
Master always injects the full contents of this table into the container spec regardless of `config_mode`. In manual mode, master additionally injects `OVERRIDE_SERVER_PROPERTIES=false`, which tells
itzg to leave `server.properties` on disk unchanged. The stored values are therefore always preserved and take effect immediately when managed mode is re-enabled — no data loss on mode switch.

    `OVERRIDE_SERVER_PROPERTIES` itself is never stored in this table; it is derived from `config_mode` at spec-build time.

---

## `server_container_metrics`

Per-container resource snapshots sourced from the Docker Stats API, collected by the agent and forwarded to master.

| Column            | Type         | Description                                                     |
|-------------------|--------------|-----------------------------------------------------------------|
| `id`              | UUID         | Primary key                                                     |
| `server_id`       | UUID         | FK → `servers`, CASCADE DELETE                                  |
| `recorded_at`     | TIMESTAMPTZ  | Snapshot timestamp                                              |
| `cpu_percent`     | NUMERIC(5,2) | Container CPU utilisation                                       |
| `ram_used_mb`     | INT          |                                                                 |
| `net_in_bytes`    | BIGINT       | Bytes received since last snapshot                              |
| `net_out_bytes`   | BIGINT       | Bytes sent since last snapshot                                  |
| `block_in_bytes`  | BIGINT       | Cumulative bytes read from block devices since container start  |
| `block_out_bytes` | BIGINT       | Cumulative bytes written to block devices since container start |

**Index:** `(server_id, recorded_at DESC)`

Subject to the same `metric_retention_days` retention policy as `node_metrics`.

!!! warning "Future consideration"
See the note in [Nodes](nodes.md#node_metrics) — this table shares the same time-series extraction considerations as `node_metrics`.

---

## `port_registry`

Tracks port assignments per node. Each server that needs a host-mapped port is assigned one from the node's configured range at creation time. Ports are reclaimed when the server is deleted.

| Column      | Type        | Description                                                                   |
|-------------|-------------|-------------------------------------------------------------------------------|
| `node_id`   | UUID        | FK → `nodes`, CASCADE DELETE                                                  |
| `port`      | INT         | Port number                                                                   |
| `protocol`  | VARCHAR(3)  | Transport protocol (`TCP` or `UDP`)                                           |
| `server_id` | UUID        | FK → `servers`, SET NULL on server delete — `NULL` indicates the port is free |

**Primary key:** `(node_id, port, protocol)`

---

## `proxy_backends`

Records which backend game servers a proxy server connects to, used in managed (easy) mode to generate `velocity.toml` or `config.yml`. Ignored when the proxy's `config_mode` is `MANUAL`.

| Column              | Type        | Description                                                                   |
|---------------------|-------------|-------------------------------------------------------------------------------|
| `id`                | UUID        | Primary key                                                                   |
| `proxy_server_id`   | UUID        | FK → `servers` (must be a proxy type), CASCADE DELETE                         |
| `backend_server_id` | UUID        | FK → `servers` (must be a game server type), CASCADE DELETE                   |
| `backend_name`      | VARCHAR(64) | Internal name used in the generated proxy config, e.g. `survival`, `creative` |
| `order`             | INT         | Ordering for display and config generation                                    |

**Unique constraint:** `(proxy_server_id, backend_name)`

---

## `server_jobs`

Holds cron-based job definitions tied to a server. Currently used for scheduled backups; extensible for future job types.

| Column            | Type         | Description                                                          |
|-------------------|--------------|----------------------------------------------------------------------|
| `id`              | UUID         | Primary key                                                          |
| `server_id`       | UUID         | FK → `servers`, CASCADE DELETE                                       |
| `type`            | VARCHAR(50)  | Job type identifier, e.g. `backup`                                   |
| `cron_expression` | VARCHAR(64)  | Cron schedule for the job                                            |
| `enabled`         | BOOLEAN      | Whether the job is active; default `true`                            |
| `last_fired_at`   | TIMESTAMPTZ  | When the job last triggered; `NULL` if never fired                   |
| `created_at`      | TIMESTAMPTZ  |                                                                      |
| `updated_at`      | TIMESTAMPTZ  |                                                                      |

**Primary key:** `(id)`
