# Backups

Base path: `/api/v1/servers/{id}`

| Method | Path | Permission | Description |
|---|---|---|---|
| GET | `/servers/{id}/backups` | `server.backup` | List backups |
| POST | `/servers/{id}/backups` | `server.backup` | Trigger a manual backup |
| DELETE | `/servers/{id}/backups/{backupId}` | `server.backup` | Delete a backup |
| GET | `/servers/{id}/backups/{backupId}/download` | `server.export` | Download backup archive |
| GET | `/servers/{id}/backup-schedule` | `server.backup` | Get backup schedule and retention config |
| PUT | `/servers/{id}/backup-schedule` | `server.backup` | Set backup schedule and retention |

---

## `GET /servers/{id}/backups`

**Response `200`:**

```json
{
  "backups": [
    {
      "id": "<uuid>",
      "trigger": "SCHEDULED",
      "status": "COMPLETED",
      "size_bytes": 524288000,
      "created_at": "2026-05-04T04:00:00Z",
      "completed_at": "2026-05-04T04:03:12Z"
    },
    {
      "id": "<uuid>",
      "trigger": "MANUAL",
      "status": "FAILED",
      "size_bytes": null,
      "error_message": "Disk full on node node-1",
      "created_at": "2026-05-03T14:00:00Z",
      "completed_at": "2026-05-03T14:00:08Z"
    }
  ]
}
```

---

## `POST /servers/{id}/backups`

Triggers an immediate manual backup. If the server's `backup_max_count` limit would be exceeded, the oldest completed backup is deleted first before the new one starts.

No request body.

**Response `202`:**

```json
{
  "backup_id": "<uuid>",
  "status": "IN_PROGRESS"
}
```

Progress and completion arrive via WebSocket.

**Errors:** `502` if the agent is unreachable.

---

## `DELETE /servers/{id}/backups/{backupId}`

Deletes the backup record and removes the archive file from the node.

**Response `204`.**

**Errors:** `409` if the backup is currently `IN_PROGRESS`.

---

## `GET /servers/{id}/backups/{backupId}/download`

Streams the backup archive from the node as a file download. Requires `server.export` permission.

**Response `200`:** binary stream with `Content-Disposition: attachment; filename="<backup-id>.tar.gz"`.

**Errors:** `409` if the backup status is not `COMPLETED`. `502` if the node is unreachable.

---

## `GET /servers/{id}/backup-schedule`

**Response `200`:**

```json
{
  "schedule": "0 4 * * *",
  "max_count": 10
}
```

`schedule` is `null` if no scheduled backups are configured.

---

## `PUT /servers/{id}/backup-schedule`

**Request:**

```json
{
  "schedule": "0 4 * * *",
  "max_count": 14
}
```

Set `schedule` to `null` to disable scheduled backups. `max_count` must be at least `1`.

**Response `200`:** updated schedule object.

**Errors:** `422` if `schedule` is not a valid cron expression.
