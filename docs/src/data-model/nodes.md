# Nodes

## Enums

### `node_status`

Lifecycle state — admin-driven transitions only.

| Value            | Meaning                                                                   |
|------------------|---------------------------------------------------------------------------|
| `PENDING`        | Node has registered via gRPC but has not yet been trusted by an admin     |
| `ACTIVE`         | Node is trusted and eligible to receive commands                          |
| `REJECTED`       | Admin explicitly rejected this node; agent cannot reconnect               |
| `DECOMMISSIONED` | Node intentionally removed; no servers should remain assigned             |

!!! note "Trust flow"
A node enters `PENDING` immediately after the agent calls `RegisterNode` over gRPC with a valid bootstrap token. Master creates the node record and returns a unique node key, but will not dispatch any
commands to the node until an admin explicitly trusts it via `POST /nodes/{id}/trust`, at which point the status moves to `ACTIVE`. A leaked bootstrap token can only produce `PENDING` records —
harmless noise an admin can delete.

### `node_health`

Runtime health — master/agent-observed, independent of lifecycle status.

| Value         | Meaning                                                                                        |
|---------------|------------------------------------------------------------------------------------------------|
| `HEALTHY`     | Agent connected and mc-router running normally                                                 |
| `DEGRADED`    | Agent connected but mc-router is down; new player connections fail, existing servers unaffected |
| `UNREACHABLE` | Agent disconnected or no heartbeat received; commands cannot be delivered                      |

!!! note "Two-axis model"
`status` and `health` are orthogonal. A node can be `ACTIVE` (admin approved) while `UNREACHABLE` (agent offline). The UI derives a display badge from both axes: `ACTIVE + HEALTHY` → Active, `ACTIVE + DEGRADED` → Degraded, `ACTIVE + UNREACHABLE` → Unreachable. Non-ACTIVE nodes always show their lifecycle status regardless of health.

---

## `nodes`

| Column                 | Type               | Description                                                                |
|------------------------|--------------------|----------------------------------------------------------------------------|
| `id`                   | UUID               | Primary key                                                                |
| `display_name`         | VARCHAR(64)        | Human-readable label set by administrator                                  |
| `hostname`             | VARCHAR(255)       | As reported by agent at registration                                       |
| `public_ip`            | INET               | Public-facing IP; used for DNS A records and player ingress                |
| `private_ip`           | INET               | Private IP; used for cross-node container communication                    |
| `token_hash`           | TEXT               | SHA-256 of the 256-bit pre-shared registration token                       |
| `status`               | VARCHAR(20)          | Admin-driven lifecycle state: `PENDING`, `ACTIVE`, `REJECTED`, or `DECOMMISSIONED`                |
| `health`               | VARCHAR(20)        | Runtime health: `HEALTHY`, `DEGRADED`, or `UNREACHABLE`. Default `HEALTHY` |
| `total_ram_mb`         | INT                | Total RAM reported by agent at registration                                |
| `total_cpu_shares`     | INT                | Configured allocatable CPU share envelope                                  |
| `system_ram_used_mb`   | INT                | RAM used by the agent host itself; reported by agent each snapshot; `NULL` if not yet collected |
| `allocated_ram_mb`     | INT                | Sum of `ram_mb` across all servers currently on this node                  |
| `allocated_cpu_shares` | INT                | Sum of `cpu_shares` across all servers currently on this node              |
| `port_range_start`     | INT                | First port in the assignable range; default `25570`                        |
| `port_range_end`       | INT                | Last port in the assignable range; default `26070`                         |
| `agent_version`        | VARCHAR(50)        | Agent version string as reported at registration; `NULL` if not provided   |
| `swarm_active`         | BOOLEAN            | `true` when agent's Docker daemon is joined to a Swarm cluster; updated on every agent connect |
| `last_seen_at`         | TIMESTAMPTZ        | Timestamp of last gRPC message received from agent                         |
| `created_at`           | TIMESTAMPTZ        |                                                                            |
| `updated_at`           | TIMESTAMPTZ        |                                                                            |

!!! note "Computed fields"
`allocated_ram_mb` and `allocated_cpu_shares` are not stored columns — they are computed at query time by summing `memory_mb` and `cpu_shares` across all servers currently assigned to the node. Master
checks available capacity (`total - allocated`) before allowing a new allocation.

!!! note "`data_path`"
`data_path` is not stored in the database — it is agent runtime configuration only, set via the `DATA_PATH` / `HOST_DATA_PATH` environment variables on each agent. It defaults to `/data` inside the agent container.

---

## `node_metrics`

Stores point-in-time snapshots of node-level resource usage collected by the agent from `/proc`. Snapshots are written by master at **1-minute intervals**.

| Column             | Type         | Description                                                                                                                 |
|--------------------|--------------|-----------------------------------------------------------------------------------------------------------------------------|
| `id`               | UUID         | Primary key                                                                                                                 |
| `node_id`          | UUID         | FK → `nodes`, CASCADE DELETE                                                                                                |
| `recorded_at`      | TIMESTAMPTZ  | Snapshot timestamp                                                                                                          |
| `cpu_percent`      | NUMERIC(5,2) | Aggregate CPU utilisation across all cores                                                                                  |
| `cpu_per_core`     | JSONB        | Array of per-core utilisation percentages                                                                                   |
| `ram_used_mb`      | INT          |                                                                                                                             |
| `ram_total_mb`     | INT          |                                                                                                                             |
| `net_in_bytes`     | BIGINT       | Cumulative bytes received across physical interfaces since last counter reset. Docker bridge and loopback traffic excluded. |
| `net_out_bytes`    | BIGINT       | Cumulative bytes sent across physical interfaces since last counter reset. Docker bridge and loopback traffic excluded.     |
| `disk_used_bytes`  | BIGINT       | Used bytes on the data partition                                                                                            |
| `disk_total_bytes` | BIGINT       | Total bytes on the data partition                                                                                           |

**Index:** `(node_id, recorded_at DESC)`

Retention is controlled by `metric_retention_days` in system settings (default 30 days). A scheduled job purges rows older than the retention window.

!!! warning "Future consideration"
At scale, `node_metrics` and `server_container_metrics` are the primary candidates for extraction to a dedicated time-series store (InfluxDB, TimescaleDB, VictoriaMetrics). Postgres is sufficient at
launch but query performance and storage growth should be monitored as node count and retention period increase.
