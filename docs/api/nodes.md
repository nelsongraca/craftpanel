# Nodes

Base path: `/api/v1/nodes`

| Method | Path | Permission | Description |
|---|---|---|---|
| GET | `/nodes` | `system.nodes` | List all nodes |
| GET | `/nodes/{id}` | `system.nodes` | Get node details |
| PATCH | `/nodes/{id}` | `system.nodes` | Update display name, port range, or data path |
| DELETE | `/nodes/{id}` | `system.nodes` | Decommission a node |
| POST | `/nodes/{id}/trust` | `system.nodes` | Trust a pending node |
| POST | `/nodes/{id}/token/rotate` | `system.nodes` | Rotate node key |
| POST | `/nodes/{id}/shutdown` | `system.nodes` | Send graceful shutdown command |
| GET | `/nodes/{id}/metrics` | `system.nodes` | Query historical node metrics |

---

## `GET /nodes`

**Response `200`:**

```json
{
  "nodes": [
    {
      "id": "<uuid>",
      "display_name": "node-1",
      "hostname": "node1.internal",
      "public_ip": "1.2.3.4",
      "private_ip": "10.0.0.1",
      "status": "ACTIVE",
      "total_ram_mb": 32768,
      "allocated_ram_mb": 18432,
      "total_cpu_shares": 1024,
      "allocated_cpu_shares": 768,
      "port_range_start": 25570,
      "port_range_end": 26070,
      "agent_version": "1.0.0",
      "created_at": "2026-05-04T10:00:00Z"
    }
  ]
}
```

---

## `PATCH /nodes/{id}`

All fields optional.

**Request:**

```json
{
  "display_name": "node-primary",
  "port_range_start": 25570,
  "port_range_end": 26070,
  "data_path": "/data/craftpanel"
}
```

**Response `200`:** updated node object.

**Errors:** `422` if the port range is invalid or overlaps with existing allocations.

---

## `DELETE /nodes/{id}`

Marks the node as `DECOMMISSIONED`.

**Response `204`.**

**Errors:** `409` if the node has servers assigned. All servers must be migrated or deleted first.

---

## `POST /nodes/{id}/trust`

Moves a `PENDING` node to `ACTIVE`. Master will begin dispatching commands to the node's agent.

**Response `204`.**

**Errors:** `409` if the node is not in `PENDING` status.

---

## `POST /nodes/{id}/token/rotate`

Generates a new node key and immediately invalidates the current one. The agent will be rejected on its next heartbeat and must be re-provisioned.

**Response `204`.**

---

## `POST /nodes/{id}/shutdown`

Sends a `ShutdownCommand` to the agent over the gRPC control stream. The agent stops all containers gracefully and exits.

**Request:**

```json
{
  "timeout_seconds": 30
}
```

**Response `202`.** Shutdown is async — the node will transition to `DEGRADED` once the agent disconnects.

**Errors:** `502` if the agent is not currently connected.

---

## `GET /nodes/{id}/metrics`

Returns raw 1-minute metric snapshots for the requested time range.

**Query parameters:**

| Param | Required | Description |
|---|---|---|
| `from` | Yes | ISO 8601 start timestamp |
| `to` | Yes | ISO 8601 end timestamp |

**Response `200`:**

```json
{
  "node_id": "<uuid>",
  "series": {
    "cpu_percent":      [{ "t": "2026-05-04T10:00:00Z", "v": 42.3 }],
    "ram_used_mb":      [{ "t": "2026-05-04T10:00:00Z", "v": 14200 }],
    "net_in_bytes":     [{ "t": "2026-05-04T10:00:00Z", "v": 1048576 }],
    "net_out_bytes":    [{ "t": "2026-05-04T10:00:00Z", "v": 524288 }],
    "disk_used_bytes":  [{ "t": "2026-05-04T10:00:00Z", "v": 107374182400 }]
  }
}
```

!!! note
    Metrics are returned as raw 1-minute snapshots. Downsampling and aggregation will be supported when metrics storage is migrated to a time-series database. See [Nodes data model](../data-model/nodes.md#node_metrics).
