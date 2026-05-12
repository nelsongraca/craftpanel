# System Settings

Base path: `/api/v1/system`

| Method | Path | Permission | Description |
|---|---|---|---|
| GET | `/system/settings` | `system.settings` | Get all settings |
| PATCH | `/system/settings` | `system.settings` | Update one or more settings |

These are runtime-configurable operational settings adjustable through the UI. Deployment-time configuration (database credentials, secrets, DNS API keys) is not managed here — see [Configuration & Secrets](../tech-stack/configuration.md).

---

## `GET /system/settings`

**Response `200`:**

```json
{
  "settings": {
    "metric_retention_days": 30,
    "default_backup_max_count": 10,
    "default_port_range_start": 25570,
    "default_port_range_end": 26070
  },
  "updated_at": "2026-05-04T10:00:00Z",
  "updated_by": "<user-uuid>"
}
```

`updated_at` and `updated_by` reflect the most recent change to any setting.

---

## `PATCH /system/settings`

Partial update — only provided keys are changed. Omitted keys are unchanged.

**Request:**

```json
{
  "metric_retention_days": 60,
  "default_backup_max_count": 14
}
```

**Response `200`:** full settings object after update.

**Errors:** `422` if `default_port_range_start` is greater than or equal to `default_port_range_end`. `422` if `metric_retention_days` or `default_backup_max_count` are less than `1`.

### Available settings

| Key | Type | Default | Description |
|---|---|---|---|
| `metric_retention_days` | integer | `30` | Days of node and container metric snapshots to retain |
| `default_backup_max_count` | integer | `10` | Default backup retention limit for newly created servers |
| `default_port_range_start` | integer | `25570` | Default start of the host port range applied to new nodes |
| `default_port_range_end` | integer | `26070` | Default end of the host port range applied to new nodes |
