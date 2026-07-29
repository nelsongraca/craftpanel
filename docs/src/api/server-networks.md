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
      "proxy_port": null,
      "description": "Main survival network",
      "domain_suffix": "mc.example.com",
      "dns_zone_id": "023e105f4ecef8ad9ca31a8482d7aca9",
      "dns_domain_suffix": "mc.example.com",
      "dns_provider_type": "CLOUDFLARE",
      "server_count": 3,
      "created_at": "2026-05-04T10:00:00Z"
    }
  ]
}
```

`dns_zone_id`, `dns_domain_suffix`, and `dns_provider_type` are `null` when no DNS provider is configured for the network. `domain_suffix` is a legacy alias of `dns_domain_suffix`; both mirror the same `cf_domain_suffix` DB column.

---

## `POST /networks`

**Request:**

```json
{
  "name": "Survival Network",
  "proxy_port": 25577,
  "description": "Main survival network",
  "domain_suffix": "mc.example.com",
  "dns_zone_id": "023e105f4ecef8ad9ca31a8482d7aca9",
  "dns_domain_suffix": "mc.example.com",
  "dns_provider_type": "CLOUDFLARE"
}
```

All fields except `name` are optional. `domain_suffix` and `dns_domain_suffix` are accepted for backwards compatibility — both write to the same column, prefer `dns_domain_suffix`. The DNS fields are required only when [exposure is enabled](../usage/enabling-public-hostnames.md) on a server that belongs to the network.

| Field                | Type   | Notes                                                                                              |
|----------------------|--------|----------------------------------------------------------------------------------------------------|
| `name`               | string | Required, unique.                                                                                  |
| `proxy_port`         | int    | Optional, host port for the network's mc-router instance.                                          |
| `description`        | string | Optional.                                                                                          |
| `dns_zone_id`        | string | Optional. 32-hex Cloudflare zone ID. Required when `DNS_PROVIDER=cloudflare` and exposing servers. |
| `dns_domain_suffix`  | string | Optional. Parent domain (e.g. `mc.example.com`); per-server subdomains become `<sub>.<this>`.      |
| `dns_provider_type`  | string | Optional. Stored but not consulted at runtime — provider selection is global via `DNS_PROVIDER`.   |

**Response `201`:**

```json
{
  "id": "<uuid>",
  "name": "Survival Network",
  "proxy_port": 25577,
  "description": "Main survival network",
  "domain_suffix": "mc.example.com",
  "dns_zone_id": "023e105f4ecef8ad9ca31a8482d7aca9",
  "dns_domain_suffix": "mc.example.com",
  "dns_provider_type": "CLOUDFLARE",
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
  "domain_suffix": "mc.example.com",
  "dns_zone_id": "023e105f4ecef8ad9ca31a8482d7aca9",
  "dns_domain_suffix": "mc.example.com",
  "dns_provider_type": "CLOUDFLARE",
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
  "description": "Updated description",
  "dns_zone_id": "023e105f4ecef8ad9ca31a8482d7aca9",
  "dns_domain_suffix": "mc.example.com",
  "dns_provider_type": "CLOUDFLARE"
}
```

`proxy_port` is not patchable — it is set at create time only. The DNS fields can be added, updated, or cleared (pass `null`) at any time, but servers already exposed will not retroactively reconcile until their exposure is toggled or the server is migrated.

**Response `204`:** no content.

---

## `DELETE /networks/{id}`

Deletes the network. Member servers are not deleted — their `network_id` is set to `null`.

**Response `204`.**
