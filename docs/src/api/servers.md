# Servers

Base path: `/api/servers`

| Method | Path                       | Permission         | Description                                                                     |
|--------|----------------------------|--------------------|---------------------------------------------------------------------------------|
| GET    | `/servers`                 | authenticated      | List servers the caller has `server.view` on                                    |
| POST   | `/servers`                 | `server.create`    | Create a server                                                                 |
| GET    | `/servers/{id}`            | `server.view`      | Get server details                                                              |
| PATCH  | `/servers/{id}`            | `server.configure` | Update display name, description, network, Minecraft version, or itzg image tag |
| DELETE | `/servers/{id}`            | `server.delete`    | Delete server and its data                                                      |
| POST   | `/servers/{id}/start`      | `server.start`     | Start the server                                                                |
| POST   | `/servers/{id}/stop`       | `server.stop`      | Stop the server                                                                 |
| POST   | `/servers/{id}/restart`    | `server.restart`   | Restart the server                                                              |
| PATCH  | `/servers/{id}/resources`  | `server.resources` | Update RAM and CPU allocation (requires Super Admin)                            |
| PATCH  | `/servers/{id}/expiration` | `server.expires`   | Set or clear the server's expiration date (stops immediately if now expired)    |
| PATCH  | `/servers/{id}/exposure`   | `server.configure` | Toggle external exposure and subdomain                                          |
| PATCH  | `/servers/{id}/disabled`   | `server.disable`   | Disable or re-enable a server (stops immediately if running)                    |
| GET    | `/servers/{id}/metrics`    | `server.view`      | Query historical container metrics                                              |
| GET    | `/servers/{id}/export`     | `server.export`    | Export server configuration as JSON                                             |
| POST   | `/servers/import`          | `server.create`    | Import a server from an exported JSON configuration                             |

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

**CUSTOM servers:**

```json
{
  "server_type": "CUSTOM",
  "custom_server_jar": "https://download.example.com/paper-build.jar",
  "container_listen_port": 25565,
  "container_protocol": "TCP",
  "disable_healthcheck": false,
  "force_redownload": true
}
```

For `CUSTOM` servers, `custom_server_jar` is **required** (a 422 is returned without it). `mc_version` is forced to `LATEST` (itzg `TYPE=CUSTOM` ignores it) and `config_mode` is always `MANUAL` — the
config-mode toggle is rejected. `container_listen_port` accepts `1`–`65535`; `container_protocol` accepts `TCP` or `UDP` (default `TCP`, uppercased server-side). `container_protocol = UDP` exposes the
container over UDP host port mappings only and skips mc-router routing.

**Response `201`:** full server object (see `GET /servers/{id}`).

**Errors:** `409` if the node has insufficient RAM or CPU capacity. `422` if `node_id` refers to a non-active node.

!!! note "Minecraft version list"
The UI populates the `mc_version` picker from the Mojang version manifest:
`GET https://launchermeta.mojang.com/mc/game/version_manifest_v2.json`

    Filter to entries where `type == "release"` and present them sorted newest-first. The `id` field (e.g. `"1.21.4"`) is the value stored in the database and passed to itzg as the `VERSION` environment variable.

!!! note "itzg image tags"
The `itzg_image_tag` field refers to the [itzg/minecraft-server](https://hub.docker.com/r/itzg/minecraft-server/tags) Docker image tag — these are date-stamped tooling releases (e.g. `2024-11-01`,
`java21`) or `latest`, **not** Minecraft version numbers. The Minecraft version is controlled separately by `mc_version`.

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
  "custom_server_jar": null,
  "container_listen_port": null,
  "container_protocol": "TCP",
  "disable_healthcheck": false,
  "force_redownload": false,
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
  "restart_pending": false,
  "disabled": false,
  "stop_command": "stop",
  "expires_at": null,
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
  "itzg_image_tag": "2024-11-01",
  "custom_server_jar": "https://download.example.com/paper-build.jar",
  "container_listen_port": 25566,
  "container_protocol": "UDP",
  "disable_healthcheck": true,
  "force_redownload": false
}
```

Set `network_id` to `null` to remove the server from its network.

`display_name`, `description`, and `network_id` take effect immediately. Spec-level fields (`mc_version`, `itzg_image_tag`, `custom_server_jar`,
`container_listen_port`, `container_protocol`, `disable_healthcheck`, `force_redownload`) are persisted and applied on the **next container start or restart**: master rebuilds the
spec and pushes it, and the agent recreates the container when the new spec differs from the one it last applied. This does **not** restart a running server — it sets `restart_pending`,
which the UI surfaces with a restart prompt. The recreate decision itself is the agent's (spec-diff); master never drives it from a flag.

The CUSTOM fields follow the same create-time rules: `custom_server_jar` is validated against the server type, and `container_listen_port` / `container_protocol` changes are persisted and applied on
the next start.

**Response `204`.**

---

## `DELETE /servers/{id}`

Removes the container, deletes server data from the node, and removes the DNS record if one exists.

**Response `204`.**

**Errors:** `409` if the server is not stopped.

---

## `POST /servers/{id}/start`

No request body. Master records desired state `RUNNING` and pushes a `ServerDesiredState` envelope; the agent creates/starts the container and converges.

**Response `202`.** The synthesized status becomes `STARTING` until the agent reports `HEALTHY`; updates arrive via WebSocket.

**Errors:** `409` if the server is already running. `502` if the agent is unreachable.

---

## `POST /servers/{id}/stop`

No request body. Master records desired state `STOPPED` and pushes a `ServerDesiredState` envelope. The agent performs a graceful stop using the server's configured `stop_command`
(sentinel values like `^C`/`SIGTERM` are delivered as real signals), falling back to Docker stop after the timeout.

**Response `202`.** The synthesized status is `STOPPING` until the agent reports `STOPPED` via WebSocket.

**Errors:** `409` if the server is already stopped. `502` if the agent is unreachable.

---

## `POST /servers/{id}/restart`

No request body. Master pushes a `ServerDesiredState` envelope with `force_restart`, using the configured `stop_command`. The agent stops the container then starts it; if the
pushed spec differs from the one the container was last applied with, the container is removed and recreated.

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

Both fields are required. Changes take effect on the **next container start or restart** — master rebuilds the spec and the agent recreates the container when the new spec differs.

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

The DNS record is created/updated/deleted immediately. The mc-router label change is **not** applied to a running container — the server is flagged `restart_pending` and the new routing names take effect on the next start or restart.

**Response `204`.**

**Errors:** `422` if the subdomain is already in use by another server.

---

## `PATCH /servers/{id}/disabled`

Disables or re-enables a server. When disabling a running server, the stop command is sent synchronously and the response confirms the container was stopped.

**Request:**

```json
{
  "disabled": true
}
```

Set to `false` to re-enable. Idempotent — re-sending the current value returns `204` without action. Extending `expires_at` while a server is disabled (from expiry) automatically re-enables it without
needing to call this endpoint.

**Response `204`.**

**Errors:** `422` invalid request body. `404` unknown server. `403` missing `server.disable` permission.

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

---

## Export

### `GET /servers/{id}/export`

Exports the server's full configuration as a downloadable JSON file. The export includes server identity, runtime type, resources, config mode, stop command, exposure settings, expiration, backup
schedule, environment variables, extra ports, mods/plugins, and proxy backends (for proxy servers).

**Permission:** `server.export`

**Response `200`:** `ServerExportData` JSON body with `Content-Disposition: attachment` header.

**Errors:** `404` unknown server.

---

## Import

### `POST /servers/import`

Creates a new server from an exported JSON configuration. The caller must specify which node to place the server on.

**Permission:** `server.create`

**Request body:**

```json
{
  "data": { /* ServerExportData */ },
  "node_id": "<target-node-uuid>",
  "network_id": "<optional-network-uuid>"
}
```

**Response `201`:** `ServerResponse` for the newly created server.

**Errors:** `409` name conflict. `422` invalid node/network ID or insufficient node capacity. `403` missing `server.create` permission.
