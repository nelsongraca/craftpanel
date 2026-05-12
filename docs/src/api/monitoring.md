# Monitoring & Alerts

Base path: `/api/v1/alerts`

| Method | Path | Permission | Description |
|---|---|---|---|
| GET | `/alerts/thresholds` | `system.settings` | List all alert thresholds |
| POST | `/alerts/thresholds` | `system.settings` | Create an alert threshold |
| DELETE | `/alerts/thresholds/{id}` | `system.settings` | Delete a threshold |
| GET | `/alerts/events` | `system.settings` | List alert events |

Node and server metrics are available via their respective endpoints — see [Nodes](nodes.md#get-nodesidmetrics) and [Servers](servers.md#get-serversidmetrics).

---

## `GET /alerts/thresholds`

**Response `200`:**

```json
{
  "thresholds": [
    {
      "id": "<uuid>",
      "scope_type": "NODE",
      "scope_id": "<node-uuid>",
      "metric": "ram_percent",
      "threshold_value": 90,
      "threshold_state": null,
      "created_at": "2026-05-04T10:00:00Z"
    },
    {
      "id": "<uuid>",
      "scope_type": "SERVER",
      "scope_id": "<server-uuid>",
      "metric": "server_health",
      "threshold_value": null,
      "threshold_state": "UNHEALTHY",
      "created_at": "2026-05-04T10:00:00Z"
    }
  ]
}
```

---

## `POST /alerts/thresholds`

Exactly one of `threshold_value` or `threshold_state` must be provided.

**Request — numeric threshold:**

```json
{
  "scope_type": "NODE",
  "scope_id": "<node-uuid>",
  "metric": "ram_percent",
  "threshold_value": 90
}
```

Fires when the metric exceeds the value.

**Request — state-based threshold:**

```json
{
  "scope_type": "SERVER",
  "scope_id": "<server-uuid>",
  "metric": "server_health",
  "threshold_state": "UNHEALTHY"
}
```

Fires when the metric equals the specified state string.

**Response `201`:** threshold object.

**Errors:** `422` if both or neither of `threshold_value` and `threshold_state` are provided.

---

## `DELETE /alerts/thresholds/{id}`

Also deletes all `alert_events` associated with this threshold.

**Response `204`.**

---

## `GET /alerts/events`

**Query parameters:**

| Param | Required | Description |
|---|---|---|
| `scope_type` | No | Filter by `NODE` or `SERVER` |
| `scope_id` | No | Filter to a specific node or server UUID |
| `active_only` | No | `true` to return only unresolved events (no `resolved_at`) |

**Response `200`:**

```json
{
  "events": [
    {
      "id": "<uuid>",
      "threshold_id": "<uuid>",
      "message": "Node node-1: RAM at 94%",
      "fired_at": "2026-05-04T10:00:00Z",
      "resolved_at": null
    },
    {
      "id": "<uuid>",
      "threshold_id": "<uuid>",
      "message": "Server Survival: status UNHEALTHY",
      "fired_at": "2026-05-03T22:00:00Z",
      "resolved_at": "2026-05-03T22:04:15Z"
    }
  ]
}
```
