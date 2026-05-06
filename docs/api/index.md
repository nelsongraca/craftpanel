# API Reference

The CraftPanel REST API is the exclusive interface between the browser and master. All endpoints are prefixed with `/api/v1`.

## Authentication

All endpoints except `POST /auth/login` and `POST /auth/refresh` require a valid JWT access token in the `Authorization` header:

```
Authorization: Bearer <access_token>
```

Access tokens are short-lived (15 minutes). Use `POST /auth/refresh` to obtain a new one using the HttpOnly refresh token cookie set at login.

## Permissions

Endpoints that require a specific permission are enforced against the caller's effective permissions for the relevant resource. Effective permissions are the union of all global group assignments plus any assignments scoped to the specific server or network being accessed.

A `403` is returned when the caller is authenticated but lacks the required permission. A `401` is returned when the token is missing or invalid.

## Error responses

HTTP status codes are the primary signal. The response body carries a human-readable message for display purposes:

```json
{
  "message": "Server must be stopped before deletion"
}
```

### Status codes

| Code | Meaning |
|---|---|
| `200` | Success with body |
| `201` | Resource created |
| `202` | Accepted — async operation started |
| `204` | Success, no body |
| `400` | Invalid request body or parameters |
| `401` | Missing or invalid access token |
| `403` | Authenticated but insufficient permission |
| `404` | Resource not found |
| `409` | State conflict — e.g. server is running, node has servers assigned |
| `422` | Validation failure — e.g. subdomain already taken, invalid port range |
| `502` | Master could not reach the agent for a node operation |

`409` indicates the request is valid but the current state of the resource prevents it. `422` indicates the input itself is semantically invalid regardless of state.

## Sections

- [Auth](auth.md)
- [Users & Groups](users-groups.md)
- [Nodes](nodes.md)
- [Server Networks](server-networks.md)
- [Servers](servers.md)
- [Configuration](configuration.md)
- [Mods](mods.md)
- [Files](files.md)
- [Backups](backups.md)
- [Migrations](migrations.md)
- [Monitoring & Alerts](monitoring.md)
- [System Settings](system-settings.md)
