# Servers

Base path: `/api/servers`

| Method | Path                      | Permission         | Description                                                                    |
|--------|---------------------------|--------------------|--------------------------------------------------------------------------------|
| GET    | `/servers`                | authenticated      | List servers the caller has `server.view` on                                   |
| POST   | `/servers`                | `server.create`    | Create a server                                                                |
| GET    | `/servers/{id}`           | `server.view`      | Get server details                                                             |
| PATCH  | `/servers/{id}`           | `server.configure` | Update display name, description, network, Minecraft version, or itzg image tag |
| DELETE | `/servers/{id}`           | `server.delete`    | Delete server and its data                                                     |
| POST   | `/servers/{id}/start`     | `server.start`     | Start the server                                                               |
| POST   | `/servers/{id}/stop`      | `server.stop`      | Stop the server                                                                |
| POST   | `/servers/{id}/restart`   | `server.restart`   | Restart the server                                                             |
| PATCH  | `/servers/{id}/resources` | `server.resources` | Update RAM and CPU allocation (requires Super Admin)                           |
| PATCH  | `/servers/{id}/exposure`  | `server.configure` | Toggle external exposure and subdomain                                         |
| GET    | `/servers/{id}/metrics`   | `server.view`      | Query historical container metrics                                             |

---

## `GET /servers`

Returns only servers the caller has at least `server.view` permission on.

**Response `200`:**

```json
{
  "servers": [
    {
      "id": "<uuid>",
      "display_name": "Survival",
      "server_type": "PAPER",
      "mc_version": "1.21.4",
      "itzg_image_tag": "latest",
      "status": "HEALTHY",
      "node_id": "<uuid>",
      "network_id": "<uuid>",
      "last_player_count": 14,
      "is_migrating": false,
      "exposed_externally": true,
      "public_subdomain": "survival"
    }
  ]
}
```

---

## `POST /servers`

**Request:**

```json
{
  "name": "survival",
  "display_name": "Survival",
  "description": "Main survival world",
  "server_type": "PAPER",
  "mc_version": "1.21.4",
  "itzg_image_tag": "latest",
  "node_id": "<uuid>",
  "network_id": "<uuid>",
  "memory_mb": 4096,
  "cpu_shares": 512
}
```

`network_id` is optional. `itzg_image_tag` defaults to `"latest"`. `cpu_shares` defaults to `0` (unlimited).

**Response `201`:** full server object (see `GET /servers/{id}`).

**Errors:** `409` if the node has insufficient RAM or CPU capacity. `422` if `node_id` refers to a non-active node.

!!! note "Minecraft version list"
The UI populates the `mc_version` picker from the Mojang version manifest:
`GET https://launchermeta.mojang.com/mc/game/version_manifest_v2.json`

    Filter to entries where `type == "release"` and present them sorted newest-first. The `id` field (e.g. `"1.21.4"`) is the value stored in the database and passed to itzg as the `VERSION` environment variable.

!!! note "itzg image tags"
The `itzg_image_tag` field refers to the [itzg/minecraft-server](https://hub.docker.com/r/itzg/minecraft-server/tags) Docker image tag — these are date-stamped tooling releases (e.g. `2024-11-01`, `java21`) or `latest`, **not** Minecraft version numbers. The Minecraft version is controlled separately by `mc_version`.

---

## `GET /servers/{id}`

**Response `200`:**

```json
{
  "id": "<uuid>",
  "display_name": "Survival",
  "description": "Main survival world",
  "server_type": "PAPER",
  "mc_version": "1.21.4",
  "itzg_image_tag": "latest",
  "node_id": "<uuid>",
  "network_id": "<uuid>",
  "status": "HEALTHY",
  "memory_mb": 4096,
  "cpu_shares": 512,
  "host_port": 25570,
  "exposed_externally": true,
  "public_subdomain": "survival",
  "last_player_count": 14,
  "last_player_names": [
    "Notch",
    "jeb_"
  ],
  "is_migrating": false,
  "stop_command": "stop",
  "last_seen_at": "2026-05-04T10:00:00Z",
  "created_at": "2026-05-04T08:00:00Z",
  "updated_at": "2026-05-04T10:00:00Z"
}
```

`is_migrating` is derived — `true` when an active `migrations` record exists for this server.

---

## `PATCH /servers/{id}`

All fields optional. Requires `server.configure`.

**Request:**

```json
{
  "display_name": "Survival SMP",
  "description": "Updated description",
  "network_id": "<uuid>",
  "mc_version": "1.21.5",
  "itzg_image_tag": "2024-11-01"
}
```

Set `network_id` to `null` to remove the server from its network.

`display_name`, `description`, and `network_id` take effect immediately. `mc_version` and `itzg_image_tag` are persisted but take effect on the **next container start** — master sets a `needs_recreate` flag and rebuilds the container spec on the next start or restart. The UI shows a "Restart required" banner after saving either field, with an option to restart immediately or defer.

**Response `204`.**

---

## `DELETE /servers/{id}`

Removes the container, deletes server data from the node, and removes the DNS record if one exists.

**Response `204`.**

**Errors:** `409` if the server is not stopped.

---

## `POST /servers/{id}/start`

No request body.

**Response `202`.** Server transitions to `STARTING`; status updates arrive via WebSocket.

**Errors:** `409` if the server is already running. `502` if the agent is unreachable.

---

## `POST /servers/{id}/stop`

No request body. Master sends the server's configured `stop_command` to container stdin, then waits before issuing Docker stop.

**Response `202`.** Server transitions through `STOPPING` to `STOPPED` via WebSocket.

**Errors:** `409` if the server is already stopped. `502` if the agent is unreachable.

---

## `POST /servers/{id}/restart`

No request body. Equivalent to stop then start, using the configured `stop_command`. If `needs_recreate` is set (due to a pending `mc_version` or `itzg_image_tag` change), the container is removed and recreated before starting.

**Response `202`.**

**Errors:** `502` if the agent is unreachable.

---

## `PATCH /servers/{id}/resources`

Updates RAM and CPU allocation. Requires `server.resources`, which is held only by Super Admins — Server Admins cannot call this endpoint.

**Request:**

```json
{
  "memory_mb": 8192,
  "cpu_shares": 1024
}
```

Both fields are required. Changes take effect on the **next container start** — master sets `needs_recreate` and rebuilds the container spec when starting or restarting.

**Response `204`.**

**Errors:** `409` if the node has insufficient remaining capacity for the new allocation.

---

## `PATCH /servers/{id}/exposure`

Toggles external exposure and sets or clears the public subdomain. Master creates, updates, or deletes the DNS record accordingly.

**Request:**

```json
{
  "exposed_externally": true,
  "public_subdomain": "survival"
}
```

When `exposed_externally` is `false`, `public_subdomain` is ignored and the existing DNS record is deleted.

**Response `204`.**

**Errors:** `422` if the subdomain is already in use by another server.

---

## `GET /servers/{id}/metrics`

Returns raw 1-minute container metric snapshots for the requested time range.

**Query parameters:**

| Param  | Required | Description              |
|--------|----------|--------------------------|
| `from` | Yes      | ISO 8601 start timestamp |
| `to`   | Yes      | ISO 8601 end timestamp   |

**Response `200`:**

```json
{
  "server_id": "<uuid>",
  "series": {
    "cpu_percent": [
      {
        "t": "2026-05-04T10:00:00Z",
        "v": 38.2
      }
    ],
    "ram_used_mb": [
      {
        "t": "2026-05-04T10:00:00Z",
        "v": 3200
      }
    ],
    "net_in_bytes": [
      {
        "t": "2026-05-04T10:00:00Z",
        "v": 204800
      }
    ],
    "net_out_bytes": [
      {
        "t": "2026-05-04T10:00:00Z",
        "v": 102400
      }
    ],
    "block_in_bytes": [
      {
        "t": "2026-05-04T10:00:00Z",
        "v": 10485760
      }
    ],
    "block_out_bytes": [
      {
        "t": "2026-05-04T10:00:00Z",
        "v": 5242880
      }
    ]
  }
}
```
