# Auth

Base path: `/api/auth`

| Method | Path               | Permission    | Description                                |
|--------|--------------------|---------------|--------------------------------------------|
| POST   | `/auth/login`      | —             | Authenticate with username and password    |
| POST   | `/auth/refresh`    | —             | Rotate refresh token, get new access token |
| POST   | `/auth/logout`     | authenticated | Revoke current session                     |
| POST   | `/auth/logout-all` | authenticated | Revoke all sessions for the current user   |
| POST   | `/auth/ws-ticket`  | authenticated | Issue a one-time WebSocket upgrade ticket  |

---

## JWT Structure

Access tokens are signed JWTs (HS256) with the following payload:

```json
{
  "sub": "<user-uuid>",
  "name": "John Doe",
  "email": "john@example.com",
  "groups": [
    "Server Admin",
    "Operator"
  ],
  "iat": 1234567890,
  "exp": 1234568790
}
```

| Claim    | Description                                                 |
|----------|-------------------------------------------------------------|
| `sub`    | User UUID — primary key in `users` table                    |
| `name`   | Display username                                            |
| `email`  | User email                                                  |
| `groups` | Names of groups the user belongs to (informational/UI only) |
| `iat`    | Issued-at timestamp                                         |
| `exp`    | Expiry timestamp — 15 minutes after issue                   |

!!! note
Permission nodes are **not** embedded in the JWT. Effective permissions are resolved from the database on every request using the `sub` claim, scoped to the resource being accessed. This ensures
permission changes take effect within 15 minutes (next token refresh) without requiring DB lookups on every request for the token itself.

    `is_active` is checked as part of every permission resolution query — inactive users are rejected even with a valid token.

---

## `POST /auth/login`

**Request:**

```json
{
  "username": "admin",
  "password": "secret"
}
```

**Response `200`:**

```json
{
  "access_token": "<jwt>",
  "expires_in": 900
}
```

Sets a `refresh_token` cookie (`HttpOnly; Secure; SameSite=Strict`). `expires_in` is seconds until the access token expires.

**Errors:** `401` if credentials are invalid or the user is inactive.

---

## `POST /auth/refresh`

No request body. Reads the refresh token from the cookie.

**Response `200`:**

```json
{
  "access_token": "<jwt>",
  "expires_in": 900
}
```

Issues a new refresh token cookie and revokes the old one (rotation on every use).

**Errors:** `401` if the cookie is missing, expired, or revoked.

---

## `POST /auth/logout`

No request body. Revokes the current session's refresh token.

**Response `204`.**

---

## `POST /auth/logout-all`

No request body. Revokes all refresh tokens for the authenticated user, ending all active sessions.

**Response `204`.**

---

## `POST /auth/ws-ticket`

Issues a single-use, short-lived ticket that can be exchanged for a WebSocket upgrade. Required because browsers cannot set `Authorization` headers on WebSocket connections, and the `refresh_token`
cookie is scoped to `Path=/api/auth` and is not sent on upgrade requests to other paths.

No request body. Requires a valid `Authorization: Bearer <access_token>` header.

**Response `200`:**

```json
{
  "ticket": "<opaque-token>",
  "expires_in": 30
}
```

`expires_in` is seconds until the ticket expires. Tickets are **single-use** — they are consumed on the first WebSocket upgrade that presents them and cannot be reused.

**Errors:** `401` if the access token is missing or invalid.

### Usage

```
POST /api/auth/ws-ticket
Authorization: Bearer <access_token>

→ { "ticket": "abc123...", "expires_in": 30 }

GET wss://<host>/api/ws/console/{server_id}?ticket=abc123...
```

The ticket is passed as the `ticket` query parameter on the WebSocket upgrade URL. Master validates the ticket, resolves the user from it, and then proceeds with normal permission checks before
accepting the upgrade.
