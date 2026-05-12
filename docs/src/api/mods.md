# Mods

Base path: `/api/v1/servers/{id}/mods`

| Method | Path | Permission | Description |
|---|---|---|---|
| GET | `/servers/{id}/mods` | `server.mods` | List installed mods |
| POST | `/servers/{id}/mods` | `server.mods` | Add a mod |
| PATCH | `/servers/{id}/mods/{modId}` | `server.mods` | Update pin strategy or pinned version |
| DELETE | `/servers/{id}/mods/{modId}` | `server.mods` | Remove a mod |
| GET | `/servers/{id}/mods/search` | `server.mods` | Search Modrinth filtered by server loader and version |

---

## `GET /servers/{id}/mods`

**Response `200`:**

```json
{
  "mods": [
    {
      "id": "<uuid>",
      "modrinth_project_id": "lithium",
      "display_name": "Lithium",
      "pin_strategy": "PINNED",
      "pinned_version_id": "mc1.21-0.13.0",
      "installed_version_id": "mc1.21-0.13.0",
      "created_at": "2026-05-04T10:00:00Z"
    }
  ]
}
```

`installed_version_id` reflects the last version downloaded by itzg. A difference between `pinned_version_id` and `installed_version_id` indicates the server has not been restarted since the pin was changed.

---

## `POST /servers/{id}/mods`

**Request:**

```json
{
  "modrinth_project_id": "lithium",
  "pin_strategy": "PINNED",
  "pinned_version_id": "mc1.21-0.13.0"
}
```

`pinned_version_id` is required when `pin_strategy` is `PINNED` and must be omitted when `pin_strategy` is `LATEST`.

**Response `201`:**

```json
{
  "id": "<uuid>",
  "modrinth_project_id": "lithium",
  "display_name": "Lithium",
  "pin_strategy": "PINNED",
  "pinned_version_id": "mc1.21-0.13.0",
  "installed_version_id": null,
  "created_at": "2026-05-04T10:00:00Z"
}
```

**Errors:** `409` if the mod is already added to this server. `422` if `modrinth_project_id` is not found on Modrinth.

---

## `PATCH /servers/{id}/mods/{modId}`

All fields optional.

**Request:**

```json
{
  "pin_strategy": "LATEST"
}
```

Or to switch to pinned:

```json
{
  "pin_strategy": "PINNED",
  "pinned_version_id": "mc1.21-0.14.0"
}
```

**Response `200`:** updated mod object.

---

## `DELETE /servers/{id}/mods/{modId}`

**Response `204`.**

---

## `GET /servers/{id}/mods/search`

Proxied Modrinth search filtered to the server's loader type and Minecraft version. Keeps API credentials server-side.

**Query parameters:**

| Param | Required | Description |
|---|---|---|
| `query` | Yes | Search string |

**Response `200`:**

```json
{
  "results": [
    {
      "project_id": "lithium",
      "display_name": "Lithium",
      "description": "Optimisation mod for Minecraft servers",
      "downloads": 4200000,
      "latest_version_id": "mc1.21-0.13.0",
      "icon_url": "https://cdn.modrinth.com/data/lithium/icon.png"
    }
  ]
}
```
