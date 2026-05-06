# Nodes

## Enums

### `node_status`

| Value | Meaning |
|---|---|
| `PENDING` | Node has registered via gRPC but has not yet been trusted by an admin |
| `ACTIVE` | Node is trusted and agent is connected and reporting normally |
| `DEGRADED` | Agent disconnected or missing heartbeat; servers on this node marked offline |
| `DECOMMISSIONED` | Node intentionally removed; no servers should remain assigned |

!!! note "Trust flow"
    A node enters `PENDING` immediately after the agent calls `RegisterNode` over gRPC with a valid bootstrap token. Master creates the node record and returns a unique node key, but will not dispatch any commands to the node until an admin explicitly trusts it via `POST /nodes/{id}/trust`, at which point the status moves to `ACTIVE`. A leaked bootstrap token can only produce `PENDING` records — harmless noise an admin can delete.

---

## `nodes`

| Column | Type | Description |
|---|---|---|
| `id` | UUID | Primary key |
| `display_name` | VARCHAR(64) | Human-readable label set by administrator |
| `hostname` | VARCHAR(255) | As reported by agent at registration |
| `public_ip` | INET | Public-facing IP; used for DNS A records and player ingress |
| `private_ip` | INET | Private IP; used for cross-node container communication |
| `token_hash` | TEXT | SHA-256 of the 256-bit pre-shared registration token |
| `status` | `node_status` ENUM | Current connectivity state |
| `total_ram_mb` | INT | Total RAM reported by agent at registration |
| `total_cpu_shares` | INT | Configured allocatable CPU share envelope |
| `allocated_ram_mb` | INT | Sum of `ram_mb` across all servers currently on this node |
| `allocated_cpu_shares` | INT | Sum of `cpu_shares` across all servers currently on this node |
| `port_range_start` | INT | First port in the assignable range; default `25570` |
| `port_range_end` | INT | Last port in the assignable range; default `26070` |
| `data_path` | TEXT | Base filesystem path for server data on this node, e.g. `/data/craftpanel` |
| `created_at` | TIMESTAMPTZ | |
| `updated_at` | TIMESTAMPTZ | |

!!! note
    `allocated_ram_mb` and `allocated_cpu_shares` are maintained by master on every server create, resize, migrate, or delete. Master checks available capacity (`total - allocated`) before allowing a new allocation.

---

## `node_metrics`

Stores point-in-time snapshots of node-level resource usage collected by the agent from `/proc`. Snapshots are written by master at **1-minute intervals**.

| Column | Type | Description |
|---|---|---|
| `id` | UUID | Primary key |
| `node_id` | UUID | FK → `nodes`, CASCADE DELETE |
| `recorded_at` | TIMESTAMPTZ | Snapshot timestamp |
| `cpu_percent` | NUMERIC(5,2) | Aggregate CPU utilisation across all cores |
| `cpu_per_core` | JSONB | Array of per-core utilisation percentages |
| `ram_used_mb` | INT | |
| `ram_total_mb` | INT | |
| `net_in_bytes` | BIGINT | Bytes received across all interfaces since last snapshot |
| `net_out_bytes` | BIGINT | Bytes sent across all interfaces since last snapshot |
| `disk_used_bytes` | BIGINT | Used bytes on the data partition |
| `disk_total_bytes` | BIGINT | Total bytes on the data partition |

**Index:** `(node_id, recorded_at DESC)`

Retention is controlled by `metric_retention_days` in system settings (default 30 days). A scheduled job purges rows older than the retention window.

!!! warning "Future consideration"
    At scale, `node_metrics` and `server_container_metrics` are the primary candidates for extraction to a dedicated time-series store (InfluxDB, TimescaleDB, VictoriaMetrics). Postgres is sufficient at launch but query performance and storage growth should be monitored as node count and retention period increase.
