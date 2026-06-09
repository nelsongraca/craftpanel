# Server Networks

Base path: `/api/networks`

| Method | Path             | Permission                             | Description                    |
|--------|------------------|----------------------------------------|--------------------------------|
| GET    | `/networks`      | authenticated                          | List all networks              |
| POST   | `/networks`      | `server.create`                        | Create a network               |
| GET    | `/networks/{id}` | authenticated                          | Get network and member servers |
| PATCH  | `/networks/{id}` | `server.configure` (scoped to network) | Update name or description     |
| DELETE | `/networks/{id}` | `server.delete` (scoped to network)    | Delete network                 |

---

## `GET /networks`

**Response `200`:**

```json
{
  "networks": [
    {
      "id": "<uuid>",
      "name": "Survival Network",
      "type": "NORMAL",
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
  "type": "NORMAL",
  "description": "Main survival network"
}
```

`type` must be `NORMAL`, `PROXY`, or `MC_PROXY`. `description` is optional.

**Response `201`:**

```json
{
  "id": "<uuid>",
  "name": "Survival Network",
  "type": "NORMAL",
  "description": "Main survival network",
  "server_count": 0,
  "created_at": "2026-05-04T10:00:00Z"
}
```

---

## `GET /networks/{id}`

**Response `200`:**

```json
{
  "id": "<uuid>",
  "name": "Survival Network",
  "type": "NORMAL",
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

**Response `200`:** updated network object.

---

## `DELETE /networks/{id}`

Deletes the network. Member servers are not deleted — their `network_id` is set to `null`.

**Response `204`.**
