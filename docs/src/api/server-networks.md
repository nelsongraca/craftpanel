# Server Networks

Base path: `/api/networks`

| Method | Path             | Permission                             | Description                    |
|--------|------------------|----------------------------------------|--------------------------------|
| GET    | `/networks`      | authenticated                          | List all networks              |
| POST   | `/networks`      | `server.create`                        | Create a network               |
| GET    | `/networks/{id}` | authenticated                          | Get network and member servers |
| PATCH  | `/networks/{id}` | `server.configure` (scoped to network) | Update name or description     |
| DELETE | `/networks/{id}` | `server.delete` (scoped to network)    | Delete network                 |

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

| Field          | Type   | Notes                                                       |
|----------------|--------|--------------------------------------------------------------|
| `name`         | string | Required, unique.                                            |
| `proxy_port`   | int    | Optional, host port for the network's mc-router instance.    |
| `description`  | string | Optional.                                                    |

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

| Condition | Message |
|---|---|
| Master has no `DOCKER_ENDPOINT` configured | `"Master is not configured with a Docker endpoint — Swarm mode required for cross-node Server Networks"` |
| One or more nodes not joined to a Swarm | `"Node(s) <names> are not joined to a Swarm — join all nodes to a Swarm before creating cross-node Server Networks"` |

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
