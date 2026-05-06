# Migrations

Base path: `/api/v1/servers/{id}/migrations`

| Method | Path | Permission | Description |
|---|---|---|---|
| GET | `/servers/{id}/migrations` | `server.migrate` | List migrations for a server |
| POST | `/servers/{id}/migrations` | `server.migrate` | Initiate a migration |
| GET | `/servers/{id}/migrations/{migrationId}` | `server.migrate` | Get migration status and step log |

---

## `GET /servers/{id}/migrations`

**Response `200`:**

```json
{
  "migrations": [
    {
      "id": "<uuid>",
      "source_node_id": "<uuid>",
      "destination_node_id": "<uuid>",
      "initiated_by": "<uuid>",
      "status": "COMPLETED",
      "current_step": 11,
      "created_at": "2026-05-04T10:00:00Z",
      "updated_at": "2026-05-04T10:08:43Z"
    }
  ]
}
```

---

## `POST /servers/{id}/migrations`

Initiates a live migration to a destination node. The server does not need to be stopped — the initial rsync runs while the server is live.

**Request:**

```json
{
  "destination_node_id": "<uuid>",
  "warning_message": "Server restarting in 60 seconds"
}
```

`warning_message` is broadcast to online players via stdin at step 4. Optional — omit to skip the in-game warning.

**Response `202`:**

```json
{
  "migration_id": "<uuid>",
  "status": "PENDING",
  "current_step": 0
}
```

**Errors:** `409` if a migration is already in progress for this server. `409` if the destination node has insufficient capacity. `422` if the destination node is not `ACTIVE`.

---

## `GET /servers/{id}/migrations/{migrationId}`

**Response `200`:**

```json
{
  "id": "<uuid>",
  "server_id": "<uuid>",
  "source_node_id": "<uuid>",
  "destination_node_id": "<uuid>",
  "initiated_by": "<uuid>",
  "status": "SYNCING",
  "current_step": 2,
  "warning_message": "Server restarting in 60 seconds",
  "error_message": null,
  "steps": [
    {
      "step": 1,
      "status": "COMPLETED",
      "message": "Migration initiated",
      "recorded_at": "2026-05-04T10:00:00Z"
    },
    {
      "step": 2,
      "status": "STARTED",
      "message": "Initial rsync started — 2.1 GB to transfer",
      "recorded_at": "2026-05-04T10:00:05Z"
    }
  ],
  "created_at": "2026-05-04T10:00:00Z",
  "updated_at": "2026-05-04T10:00:05Z"
}
```

### Migration steps reference

| Step | Description |
|---|---|
| 1 | Migration initiated; nodes selected |
| 2 | Initial rsync started (server still running) |
| 3 | Initial rsync complete |
| 4 | In-game warning broadcast via stdin |
| 5 | `save-all` + `save-off` sent to server via stdin |
| 6 | Final incremental rsync started |
| 7 | Final rsync complete; new container created and started on destination |
| 8 | DNS A record updated to destination node IP |
| 9 | mc-router label applied; ingress live on destination |
| 10 | Old container stopped and removed from source node |
| 11 | Server node assignment updated in database — migration complete |

Live progress during steps 2 and 6 (rsync) is streamed via WebSocket as `rsync_progress` events.
