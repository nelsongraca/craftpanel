# Scheduled Jobs

Base path: `/api/servers/{id}`

Managing a server's scheduled jobs requires `server.cron`. **Creating** a job additionally requires
the permission for the action it performs (`server.start`, `server.stop`, `server.restart`, or
`server.console`).

| Method | Path                          | Permission                                          | Description                |
|--------|-------------------------------|-----------------------------------------------------|----------------------------|
| GET    | `/servers/{id}/jobs`          | `server.cron`                                       | List a server's jobs       |
| POST   | `/servers/{id}/jobs`          | `server.cron` + action permission                   | Create a job               |
| PATCH  | `/servers/{id}/jobs/{jobId}`  | `server.cron`                                       | Update a job               |
| DELETE | `/servers/{id}/jobs/{jobId}`  | `server.cron`                                       | Delete a job               |
| GET    | `/system/job-types`           | _(any authenticated user)_                          | List schedulable job types |

---

## `GET /servers/{id}/jobs`

**Response `200`:**

```json
[
  {
    "id": "<uuid>",
    "server_id": "<uuid>",
    "type": "RESTART",
    "cron_expression": "0 4 * * *",
    "payload": null,
    "enabled": true,
    "last_fired_at": "2026-05-04T04:00:00Z"
  },
  {
    "id": "<uuid>",
    "server_id": "<uuid>",
    "type": "RCON_COMMAND",
    "cron_expression": "*/15 * * * *",
    "payload": "say Server restarting soon",
    "enabled": true,
    "last_fired_at": null
  }
]
```

---

## `POST /servers/{id}/jobs`

**Request:**

```json
{
  "type": "RCON_COMMAND",
  "cron_expression": "*/15 * * * *",
  "payload": "say Server restarting soon",
  "enabled": true
}
```

`payload` is required for `RCON_COMMAND` (a single console command, up to 512 characters) and
ignored for every other type.

**Response `201`:** the created job.

**Errors:** `422` if the cron expression is invalid, or if a `RCON_COMMAND` job has a missing or
over-long command. `403` if the caller lacks the action's permission. `404` if the server does not
exist.

---

## `PATCH /servers/{id}/jobs/{jobId}`

**Request** (all fields optional; omitted fields are left unchanged):

```json
{
  "cron_expression": "0 */6 * * *",
  "payload": "say Save incoming",
  "enabled": false
}
```

**Response `200`:** the updated job.

**Errors:** `422` if the cron expression is invalid. `404` if the job does not exist or belongs to a
different server.

---

## `DELETE /servers/{id}/jobs/{jobId}`

**Response `204`.**

**Errors:** `404` if the job does not exist or belongs to a different server.

---

## `GET /system/job-types`

Enumerates the job types the API accepts. Each type is backed by a registered scheduler handler.

**Response `200`:**

```json
{
  "types": ["START", "STOP", "RESTART", "RCON_COMMAND"]
}
```
