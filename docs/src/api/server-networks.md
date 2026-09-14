# Server Networks

Base path: `/api/networks`

| Method | Path                    | Permission                                 | Description                                                    |
|--------|-------------------------|--------------------------------------------|----------------------------------------------------------------|
| GET    | `/networks`             | authenticated (filtered by `network.view`) | List viewable networks                                         |
| POST   | `/networks`             | `network.create`                           | Create a network                                               |
| GET    | `/networks/{id}`        | `network.view` (scoped to network)         | Get network and member servers                                 |
| PATCH  | `/networks/{id}`        | `network.configure` (scoped to network)    | Update name or description                                     |
| DELETE | `/networks/{id}`        | `network.delete` (scoped to network)       | Delete network                                                 |
| GET    | `/networks/{id}/export` | `network.view`                             | Export network and all member servers as JSON                  |
| POST   | `/networks/import`      | `network.create`                           | Import a network with all member servers from an exported JSON |

`GET /networks` returns only networks the caller can view: all of them with GLOBAL or `*` `network.view`, the matching subset with NETWORK-scoped `network.view`, and an empty list otherwise.
`GET /networks/{id}` returns `403` unless the caller has `network.view` on that specific network.

DNS configuration (zone ID, domain suffix) is global, not per-network — see [System Settings](system-settings.md) and [Enabling Public Hostnames](../usage/enabling-public-hostnames.md).

---

## `GET /networks`

**Response `200`:**

```json
{
  "networks": [
    {
      "id": "<uuid>",
      "name": "Survival Network",
      "proxy_port": null,
      "description": "Main survival network",
      "server_count": 3,
      "created_at": "2026-05-04T10:00:00Z"
    }
  ]
}
```

---

## `POST /networks`

**Request:**

```json
{
  "name": "Survival Network",
  "proxy_port": 25577,
  "description": "Main survival network"
}
```

All fields except `name` are optional.

| Field         | Type   | Notes                                                     |
|---------------|--------|-----------------------------------------------------------|
| `name`        | string | Required, unique.                                         |
| `proxy_port`  | int    | Optional, host port for the network's mc-router instance. |
| `description` | string | Optional.                                                 |

**Response `201`:**

```json
{
  "id": "<uuid>",
  "name": "Survival Network",
  "proxy_port": 25577,
  "description": "Main survival network",
  "server_count": 0,
  "created_at": "2026-05-04T10:00:00Z"
}
```

**Response `422`:** returned when assigning servers across nodes without the required Swarm infrastructure:

| Condition                                  | Message                                                                                                              |
|--------------------------------------------|----------------------------------------------------------------------------------------------------------------------|
| Master has no `DOCKER_ENDPOINT` configured | `"Master is not configured with a Docker endpoint — Swarm mode required for cross-node Server Networks"`             |
| One or more nodes not joined to a Swarm    | `"Node(s) <names> are not joined to a Swarm — join all nodes to a Swarm before creating cross-node Server Networks"` |

---

## `GET /networks/{id}`

**Response `200`:**

```json
{
  "id": "<uuid>",
  "name": "Survival Network",
  "proxy_port": 25577,
  "description": "Main survival network",
  "servers": [
    {
      "id": "<uuid>",
      "display_name": "Proxy",
      "server_type": "VELOCITY",
      "status": "HEALTHY"
    },
    {
      "id": "<uuid>",
      "display_name": "Survival",
      "server_type": "PAPER",
      "status": "HEALTHY"
    }
  ],
  "created_at": "2026-05-04T10:00:00Z"
}
```

---

## `PATCH /networks/{id}`

All fields optional.

**Request:**

```json
{
  "name": "Main Network",
  "description": "Updated description"
}
```

`proxy_port` is not patchable — it is set at create time only.

**Response `204`:** no content.

---

## `DELETE /networks/{id}`

Deletes the network. Member servers are not deleted — their `network_id` is set to `null`.

**Response `204`.**

---

## `GET /networks/{id}/export`

Exports the network's configuration and all its member servers as a downloadable JSON file. Each member server is exported in full (including env vars, mods, ports, etc.).

**Permission:** `network.view`

**Response `200`:** `NetworkExportData` JSON body containing the network name, description, proxy port, and an array of `ServerExportData` objects.

**Errors:** `404` unknown network.

---

## `POST /networks/import`

Creates a new network and optionally imports member servers from an exported JSON configuration. The caller must specify which node each server should be created on.

**Permission:** `network.create`

**Request body:**

```json
{
  "data": { /* NetworkExportData */ },
  "node_assignments": {
    "server-name-1": "<node-uuid>",
    "server-name-2": "<node-uuid>"
  }
}
```

`node_assignments` maps every server name in the export data to a node UUID. If a server in the export is not listed, the import fails with `422`.

**Response `201`:** `NetworkResponse` for the newly created network.

**Errors:** `409` name conflict. `422` missing or invalid node assignment. `403` missing `network.create` permission.
