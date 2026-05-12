# Server Configuration

Base path: `/api/v1/servers/{id}/config`

| Method | Path | Permission | Description |
|---|---|---|---|
| GET | `/servers/{id}/config` | `server.configure` | Get config mode, stop command, and env vars |
| PUT | `/servers/{id}/config/mode` | `server.configure` | Switch config mode |
| PUT | `/servers/{id}/config/env` | `server.configure` | Replace full env var set |
| PATCH | `/servers/{id}/config/env` | `server.configure` | Update individual env vars |
| DELETE | `/servers/{id}/config/env/{key}` | `server.configure` | Delete a single env var |
| PATCH | `/servers/{id}/config/stop-command` | `server.configure` | Update the stop command |
| GET | `/servers/{id}/config/proxy` | `server.configure` | Get proxy backend list |
| PUT | `/servers/{id}/config/proxy` | `server.configure` | Replace proxy backend list |

---

## `GET /servers/{id}/config`

**Response `200`:**

```json
{
  "config_mode": "MANAGED",
  "stop_command": "stop",
  "env_vars": {
    "DIFFICULTY": "hard",
    "MAX_PLAYERS": "50",
    "MOTD": "Welcome to the server"
  }
}
```

`env_vars` is always returned but only applied to the container spec when `config_mode` is `MANAGED`.

---

## `PUT /servers/{id}/config/mode`

Switches between `MANAGED` and `MANUAL`. Stored env vars are preserved when switching to `MANUAL` and restored when switching back.

**Request:**

```json
{
  "config_mode": "MANUAL"
}
```

**Response `200`:** updated config object.

---

## `PUT /servers/{id}/config/env`

Full replacement of the env var set. Any keys not included in the request are deleted.

**Request:**

```json
{
  "env_vars": {
    "DIFFICULTY": "hard",
    "MAX_PLAYERS": "100",
    "MOTD": "Welcome"
  }
}
```

**Response `200`:** full env var map after replacement.

**Errors:** `409` if `config_mode` is `MANUAL`.

---

## `PATCH /servers/{id}/config/env`

Partial update — only the provided keys are affected. Existing keys not in the request are unchanged.

**Request:**

```json
{
  "env_vars": {
    "MAX_PLAYERS": "75"
  }
}
```

**Response `200`:** full env var map after update.

**Errors:** `409` if `config_mode` is `MANUAL`.

---

## `DELETE /servers/{id}/config/env/{key}`

Removes a single env var.

**Response `204`.**

**Errors:** `404` if the key does not exist. `409` if `config_mode` is `MANUAL`.

---

## `PATCH /servers/{id}/config/stop-command`

Updates the command written to container stdin on graceful stop or restart.

**Request:**

```json
{
  "stop_command": "end"
}
```

Set to an empty string to skip the stdin command and go straight to Docker stop.

**Response `200`:** updated config object.

---

## `GET /servers/{id}/config/proxy`

Only applicable to proxy server types (`VELOCITY`, `BUNGEECORD`, `WATERFALL`).

**Response `200`:**

```json
{
  "backends": [
    {
      "id": "<uuid>",
      "backend_server_id": "<uuid>",
      "backend_name": "survival",
      "order": 1
    },
    {
      "id": "<uuid>",
      "backend_server_id": "<uuid>",
      "backend_name": "creative",
      "order": 2
    }
  ]
}
```

**Errors:** `409` if the server is not a proxy type.

---

## `PUT /servers/{id}/config/proxy`

Full replacement of the backend list. Backends not included are removed.

**Request:**

```json
{
  "backends": [
    {
      "backend_server_id": "<uuid>",
      "backend_name": "survival",
      "order": 1
    },
    {
      "backend_server_id": "<uuid>",
      "backend_name": "creative",
      "order": 2
    }
  ]
}
```

**Response `200`:** updated backend list.

**Errors:** `409` if the server is not a proxy type. `422` if `backend_name` values are not unique within the list. `422` if any `backend_server_id` refers to a proxy type server.
